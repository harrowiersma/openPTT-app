/*
 * BLE GATT client for the Hytera POA121 ring (and similar BLE-only PTT
 * accessories that use Hytera's vendor service).
 *
 * The POA121 doesn't expose its PTT button via the standard HID profile,
 * so MediaSession / KeyEvent capture never sees the press. Instead it
 * pushes 8-byte frames over GATT notifications after we subscribe to the
 * vendor characteristic 00001014-d102-11e1-9b23-00025b00a5a5 in service
 * 00001016-... The frames look like:
 *
 *   02 53 04 02 00 07 A0 03   <- PTT pressed
 *   02 53 04 02 00 08 9F 03   <- PTT released
 *
 * Byte 5 carries the event (0x07 = down, 0x08 = up). Byte 6 is a
 * checksum (byte5 + byte6 == 0xA7). 0x02/0x03 frame STX/ETX.
 *
 * The radio's Bluetooth stack drops the LE connection every ~15s due to
 * GATT_CONN_TIMEOUT — connectGatt(autoConnect=true) auto-reattaches.
 * Each reconnect re-runs service discovery + subscribe to re-arm the
 * notification pipeline.
 */
package se.lublin.mumla.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class BtPttGattHandler {
    private static final String TAG = "BtPttGatt";
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onBtPttDown();
        void onBtPttUp();
    }

    private final Context mContext;
    private final Listener mListener;
    private BluetoothGatt mGatt;
    private final Deque<BluetoothGattCharacteristic> mSubscribeQueue = new ArrayDeque<>();
    private volatile boolean mStarted;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private BluetoothDevice mTarget;
    private BluetoothManager mBm;

    /** True between a received PTT-down frame and its matching up frame.
     *  Tracked so we can force-release if the BLE link drops during a
     *  press (the up frame would otherwise be lost in the disconnect
     *  window — a stuck-on transmitter). */
    private volatile boolean mTalking;

    /** Set once onServicesDiscovered fires for the current connection.
     *  Used by the kick-start path: when Android's stack reports the
     *  device is already CONNECTED at connectGatt time, no stateChange
     *  callback fires (nothing to change to), and we'd sit idle. We
     *  force-call discoverServices() after a short delay if this is
     *  still false. */
    private volatile boolean mServicesDiscovered;

    /** Maximum continuous transmit time enforced as a watchdog. Standard
     *  practice on commercial radios; protects against a missed or
     *  malformed up frame leaving us keyed indefinitely. */
    private static final long MAX_TX_MS = 60_000L;

    /** Grace window after a GATT disconnect-while-talking before we
     *  force-release. If the BLE link comes back and the firmware re-sends
     *  a DOWN frame within this window (because the user is still holding),
     *  we cancel the release and TX stays on — no audible hiccup. The
     *  POA121's autoConnect reconnect typically completes in ~1-1.5s, so
     *  3s leaves comfortable margin. The 60s TX watchdog is still the
     *  absolute backstop. */
    private static final long DISCONNECT_GRACE_MS = 3_000L;

    public BtPttGattHandler(@NonNull Context context, @NonNull Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        // Ensure we run on the main looper — connectGatt has historically
        // misbehaved when called from threads without a Looper, and the
        // HumlaObserver dispatch thread isn't guaranteed.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(this::start);
            return;
        }
        startInternal();
    }

    @SuppressLint("MissingPermission")
    private synchronized void startInternal() {
        if (mStarted) { Log.i(TAG, "already started"); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(mContext,
                        Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted; BT PTT disabled");
            return;
        }
        mBm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBm == null) { Log.w(TAG, "no BluetoothManager"); return; }
        BluetoothAdapter adapter = mBm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { Log.w(TAG, "BT off"); return; }

        mTarget = findRing(adapter);
        if (mTarget == null) {
            Log.i(TAG, "No POA*/Hytera bonded device — BT PTT idle");
            return;
        }
        int curState = mBm.getConnectionState(mTarget, BluetoothProfile.GATT);
        Log.i(TAG, "starting GATT to " + mTarget.getAddress()
                + " initial state=" + stateToString(curState));
        mServicesDiscovered = false;
        try {
            mGatt = mTarget.connectGatt(mContext, /*autoConnect=*/ true, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            Log.i(TAG, "connectGatt returned " + (mGatt == null ? "null" : "non-null"));
            mStarted = (mGatt != null);
            if (mStarted) {
                startHeartbeat();
                // If the underlying ACL is already up, onConnectionStateChange
                // will not fire (no state delta to deliver). Force the
                // discoverServices() path after a short settle delay so the
                // subscription chain still runs.
                mMainHandler.postDelayed(mKickStart, 1500L);
            }
        } catch (SecurityException se) {
            Log.w(TAG, "connectGatt SecurityException: " + se.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private final Runnable mKickRetry = () -> {
        if (!mStarted || mGatt == null || mServicesDiscovered) return;
        Log.w(TAG, "kick-retry: still no servicesDiscovered, hard-cycling gatt");
        // Last-resort recovery: tear the gatt down and reconnect. Android
        // sometimes wedges a GATT client when the bonded device's cached
        // attribute table is out of sync with the firmware version.
        try { mGatt.disconnect(); mGatt.close(); } catch (Exception ignored) {}
        mGatt = null;
        mStarted = false;
        mServicesDiscovered = false;
        start();
    };

    @SuppressLint("MissingPermission")
    private final Runnable mKickStart = () -> {
        if (!mStarted || mGatt == null || mServicesDiscovered) return;
        // Stale GATT cache after a previous session can swallow our
        // discovery request — refresh() (hidden API) clears it so the
        // next discoverServices() forces a fresh round-trip.
        boolean refreshed = refreshGattCache(mGatt);
        Log.i(TAG, "kick-start: refresh=" + refreshed + ", discoverServices()");
        try { mGatt.discoverServices(); }
        catch (SecurityException ignored) {}
        // If still nothing after another 3s, retry once more.
        mMainHandler.postDelayed(mKickRetry, 3000L);
    };

    private static boolean refreshGattCache(BluetoothGatt gatt) {
        // BluetoothGatt.refresh() is @hide; reflection is the documented
        // workaround. Returns true if the refresh request was queued.
        try {
            Method m = gatt.getClass().getMethod("refresh");
            Object r = m.invoke(gatt);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            Log.w(TAG, "refresh() reflection failed: " + e);
            return false;
        }
    }

    private final Runnable mHeartbeat = new Runnable() {
        int n = 0;
        @Override
        public void run() {
            if (!mStarted || mBm == null || mTarget == null) return;
            int s = mBm.getConnectionState(mTarget, BluetoothProfile.GATT);
            Log.i(TAG, "heartbeat #" + (++n) + " state=" + stateToString(s));
            // First 5 heartbeats every 3s, then back off to every 30s.
            mMainHandler.postDelayed(this, n < 5 ? 3000L : 30000L);
        }
    };

    private void startHeartbeat() {
        mMainHandler.removeCallbacks(mHeartbeat);
        mMainHandler.postDelayed(mHeartbeat, 3000);
    }

    private static String stateToString(int s) {
        switch (s) {
            case BluetoothProfile.STATE_DISCONNECTED:  return "DISCONNECTED";
            case BluetoothProfile.STATE_CONNECTING:    return "CONNECTING";
            case BluetoothProfile.STATE_CONNECTED:     return "CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return "?(" + s + ")";
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        forceReleaseIfTalking("handler stop");
        mStarted = false;
        mServicesDiscovered = false;
        mMainHandler.removeCallbacks(mHeartbeat);
        mMainHandler.removeCallbacks(mKickStart);
        mMainHandler.removeCallbacks(mKickRetry);
        mMainHandler.removeCallbacks(mDisconnectGrace);
        if (mGatt != null) {
            try { mGatt.disconnect(); mGatt.close(); } catch (Exception ignored) {}
            mGatt = null;
        }
        mSubscribeQueue.clear();
        mTarget = null;
        Log.i(TAG, "stopped");
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findRing(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException e) { return null; }
        for (BluetoothDevice d : bonded) {
            String name;
            try { name = d.getName(); } catch (SecurityException e) { name = null; }
            if (name == null) continue;
            String up = name.toUpperCase(Locale.ROOT);
            // POA121 is the only ring we've tested; POA61/POA63 use the same
            // vendor service per Hytera docs. Treat any POA* and any "Hytera"
            // BLE accessory as a candidate.
            if (up.contains("POA") || up.contains("HYTERA")) {
                return d;
            }
        }
        return null;
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i(TAG, "stateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Discover services every connect — the GATT cache is cleared
                // on GATT_CONN_TIMEOUT auto-reconnects, so we re-arm subscriptions.
                mServicesDiscovered = false;
                mMainHandler.removeCallbacks(mKickStart);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mServicesDiscovered = false;
                // If we're not transmitting, nothing to do. If we are,
                // start the grace timer — see DISCONNECT_GRACE_MS comment.
                // A still-held button will re-fire DOWN on the autoConnect
                // reattach, which cancels the timer (no audible TX gap).
                // A genuinely released button means no DOWN arrives and
                // the timer fires, force-releasing TX.
                if (mTalking) {
                    Log.i(TAG, "BLE disconnected mid-press — grace timer "
                            + (DISCONNECT_GRACE_MS / 1000) + "s");
                    mMainHandler.removeCallbacks(mDisconnectGrace);
                    mMainHandler.postDelayed(mDisconnectGrace, DISCONNECT_GRACE_MS);
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "servicesDiscovered status=" + status);
            mServicesDiscovered = true;
            mMainHandler.removeCallbacks(mKickStart);
            mMainHandler.removeCallbacks(mKickRetry);
            mSubscribeQueue.clear();
            for (BluetoothGattService svc : gatt.getServices()) {
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int p = ch.getProperties();
                    // Subscribe to every notify/indicate characteristic — the
                    // POA121 sends PTT frames as unsolicited writes that we
                    // observe on whichever char our local stack happens to
                    // route them to. Subscribing broadly is the most robust.
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        mSubscribeQueue.addLast(ch);
                    }
                }
            }
            subscribeNext(gatt);
        }

        @SuppressLint("MissingPermission")
        private void subscribeNext(BluetoothGatt gatt) {
            BluetoothGattCharacteristic ch = mSubscribeQueue.pollFirst();
            if (ch == null) { Log.i(TAG, "all notify chars subscribed"); return; }
            if (!gatt.setCharacteristicNotification(ch, true)) {
                subscribeNext(gatt);
                return;
            }
            BluetoothGattDescriptor desc = ch.getDescriptor(CCCD_UUID);
            if (desc == null) { subscribeNext(gatt); return; }
            boolean indicate = (ch.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
            byte[] value = indicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, value);
            } else {
                desc.setValue(value);
                gatt.writeDescriptor(desc);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            subscribeNext(gatt);
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt,
                                            @NonNull BluetoothGattCharacteristic characteristic,
                                            @NonNull byte[] value) {
            decode(value);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;
            decode(characteristic.getValue());
        }
    };

    /**
     * POA121 PTT frame format observed:
     *   byte 0   = 0x02     STX
     *   byte 1-4 = 53 04 02 00  fixed header
     *   byte 5   = event (0x07 down, 0x08 up)
     *   byte 6   = checksum: byte5 + byte6 == 0xA7
     *   byte 7   = 0x03     ETX
     *
     * Anything else (battery level updates on 0x180F, etc.) is ignored.
     */
    private void decode(byte[] v) {
        if (v == null || v.length != 8) return;
        if (v[0] != 0x02 || v[7] != 0x03) return;
        if (v[1] != 0x53 || v[2] != 0x04 || v[3] != 0x02 || v[4] != 0x00) return;
        int event = v[5] & 0xff;
        int checksum = v[6] & 0xff;
        if (((event + checksum) & 0xff) != 0xA7) {
            Log.w(TAG, "checksum mismatch event=0x" + Integer.toHexString(event)
                    + " checksum=0x" + Integer.toHexString(checksum));
            return;
        }
        if (event == 0x07) {
            boolean wasTalking = mTalking;
            mTalking = true;
            mMainHandler.removeCallbacks(mTxWatchdog);
            mMainHandler.postDelayed(mTxWatchdog, MAX_TX_MS);
            if (wasTalking) {
                // We were already in talking state — this DOWN arrived
                // during a disconnect-grace window. Cancel the pending
                // force-release and DON'T re-call onTalkKeyDown (TX is
                // already on; calling it again would emit a stray sound
                // and could confuse downstream toggle logic).
                Log.i(TAG, "PTT DOWN (in-grace re-arm; TX uninterrupted)");
                mMainHandler.removeCallbacks(mDisconnectGrace);
            } else {
                Log.i(TAG, "PTT DOWN");
                mListener.onBtPttDown();
            }
        } else if (event == 0x08) {
            Log.i(TAG, "PTT UP");
            mTalking = false;
            mMainHandler.removeCallbacks(mTxWatchdog);
            mMainHandler.removeCallbacks(mDisconnectGrace);
            mListener.onBtPttUp();
        }
    }

    /** Called when the BLE link drops mid-press, on stop(), or when the
     *  TX watchdog fires. Forces a synthetic UP so the service can't be
     *  left keyed indefinitely. Idempotent — no-op when not transmitting. */
    private void forceReleaseIfTalking(String reason) {
        if (!mTalking) return;
        mTalking = false;
        mMainHandler.removeCallbacks(mTxWatchdog);
        mMainHandler.removeCallbacks(mDisconnectGrace);
        Log.w(TAG, "force PTT UP: " + reason);
        // Marshal to main thread so listeners see a consistent order with
        // their normal up frames (which arrive via the GATT binder thread
        // and then are forwarded to onTalkKeyUp on whatever thread the
        // listener uses internally).
        mMainHandler.post(mListener::onBtPttUp);
    }

    private final Runnable mTxWatchdog = () ->
            forceReleaseIfTalking("TX watchdog (" + (MAX_TX_MS / 1000) + "s)");

    private final Runnable mDisconnectGrace = () ->
            forceReleaseIfTalking("disconnect grace expired");
}
