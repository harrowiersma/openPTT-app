/*
 * Bluetooth LE GATT probe activity. Connects to a bonded Hytera POA121
 * ring, enumerates every service + characteristic, subscribes to all
 * notification/indication characteristics, and logs everything that fires
 * to the on-screen TextView + a file on /sdcard.
 *
 * The aim is to identify which characteristic UUID emits PTT-button events
 * and what byte pattern represents press vs release, so a proper handler
 * can be wired into MumlaService later.
 */
package se.lublin.mumla.preference;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import se.lublin.mumla.R;

public class BtPttProbeActivity extends AppCompatActivity {
    private static final String TAG = "BtPttProbe";
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQ_BT_CONNECT = 1001;

    private TextView mLog;
    private ScrollView mScroll;
    private Button mStart, mStop, mSave, mClear;
    private final Handler mUi = new Handler(Looper.getMainLooper());

    private BluetoothGatt mGatt;
    private final Deque<BluetoothGattCharacteristic> mSubscribeQueue = new ArrayDeque<>();
    private final long mStartMs = SystemClock.elapsedRealtime();
    private final StringBuilder mDump = new StringBuilder();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bt_ptt_probe);
        mLog = findViewById(R.id.probe_log);
        mLog.setMovementMethod(new ScrollingMovementMethod());
        mScroll = findViewById(R.id.probe_scroll);
        mStart = findViewById(R.id.probe_start);
        mStop = findViewById(R.id.probe_stop);
        mSave = findViewById(R.id.probe_save);
        mClear = findViewById(R.id.probe_clear);

        mStart.setOnClickListener(v -> requestPermissionThenStart());
        mStop.setOnClickListener(v -> stopProbe(true));
        mClear.setOnClickListener(v -> { mLog.setText(""); mDump.setLength(0); });
        mSave.setOnClickListener(v -> saveToFile());

        log("Press Start. Then press the POA121's PTT button a few times.");
    }

    private void requestPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_CONNECT);
                return;
            }
        }
        startProbe();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startProbe();
            } else {
                log("BLUETOOTH_CONNECT denied. Grant it in app permissions and retry.");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startProbe() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { log("No BluetoothManager"); return; }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { log("BT off"); return; }

        BluetoothDevice target = null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        for (BluetoothDevice d : bonded) {
            String name = safeName(d);
            log("Bonded: " + d.getAddress() + " name=" + name + " type=" + d.getType());
            if (name != null && (name.toUpperCase(Locale.ROOT).contains("POA")
                    || name.toUpperCase(Locale.ROOT).contains("HYTERA"))) {
                target = d;
            }
        }
        if (target == null) {
            log("No POA*/Hytera bonded device found. Pair the ring first.");
            return;
        }
        // Show what the BT manager thinks the device's GATT state is RIGHT
        // NOW — useful for distinguishing "system is already connected so we
        // can hook on" vs "system has never connected".
        int curState = bm.getConnectionState(target, BluetoothProfile.GATT);
        log("BT manager GATT state for " + target.getAddress() + ": "
                + stateToString(curState) + " (" + curState + ")");

        // Close any prior client gatt before retrying.
        closeGatt();

        log("connectGatt(autoConnect=true, TRANSPORT_LE) ...");
        mStart.setEnabled(false);
        try {
            mGatt = target.connectGatt(this, /*autoConnect=*/ true, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            log("connectGatt returned " + (mGatt == null ? "null" : "non-null"));
        } catch (SecurityException se) {
            log("SecurityException: " + se.getMessage());
            mStart.setEnabled(true);
            return;
        }
        startHeartbeat(bm, target);
    }

    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override public void run() { /* set in startHeartbeat */ }
    };
    private Runnable mHeartbeat;

    @SuppressLint("MissingPermission")
    private void startHeartbeat(final BluetoothManager bm, final BluetoothDevice target) {
        if (mHeartbeat != null) mUi.removeCallbacks(mHeartbeat);
        mHeartbeat = new Runnable() {
            int n = 0;
            @Override
            public void run() {
                if (mGatt == null) return;
                int s = bm.getConnectionState(target, BluetoothProfile.GATT);
                log("  heartbeat #" + (++n) + " state=" + stateToString(s));
                mUi.postDelayed(this, 3000);
            }
        };
        mUi.postDelayed(mHeartbeat, 3000);
    }

    private static String stateToString(int state) {
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_CONNECTING:   return "CONNECTING";
            case BluetoothProfile.STATE_CONNECTED:    return "CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return "?";
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (mGatt != null) {
            try { mGatt.disconnect(); mGatt.close(); } catch (Exception ignored) {}
            mGatt = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopProbe(boolean user) {
        if (mHeartbeat != null) { mUi.removeCallbacks(mHeartbeat); mHeartbeat = null; }
        if (mGatt != null) {
            try {
                mGatt.disconnect();
                mGatt.close();
            } catch (Exception ignored) {}
            mGatt = null;
            log(user ? "Disconnected (user)." : "Disconnected.");
        }
        mStart.setEnabled(true);
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (SecurityException e) { return "<perm>"; }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            log("onConnectionStateChange status=" + status
                    + " newState=" + stateToString(newState));
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Requesting larger MTU before discovery");
                if (!gatt.requestMtu(247)) {
                    log("requestMtu returned false; calling discoverServices directly");
                    gatt.discoverServices();
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            log("MTU = " + mtu + " (status=" + status + "); discovering services");
            gatt.discoverServices();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log("Services discovered (status=" + status + ")");
            mSubscribeQueue.clear();
            for (BluetoothGattService svc : gatt.getServices()) {
                log("SVC " + svc.getUuid());
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    log("  CHR " + ch.getUuid() + " props=0x"
                            + Integer.toHexString(ch.getProperties()) + " ("
                            + propsToString(ch.getProperties()) + ")");
                    int p = ch.getProperties();
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        mSubscribeQueue.addLast(ch);
                    }
                }
            }
            log("Subscribing to " + mSubscribeQueue.size() + " notify/indicate chars ...");
            subscribeNext(gatt);
        }

        @SuppressLint("MissingPermission")
        private void subscribeNext(BluetoothGatt gatt) {
            BluetoothGattCharacteristic ch = mSubscribeQueue.pollFirst();
            if (ch == null) { log("Subscriptions complete. Press the PTT button now."); return; }
            boolean ok = gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor desc = ch.getDescriptor(CCCD_UUID);
            if (!ok || desc == null) {
                log("  setCharNotification failed for " + ch.getUuid()
                        + " (ok=" + ok + " desc=" + (desc != null) + ")");
                subscribeNext(gatt);
                return;
            }
            boolean indicate = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
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
            log("  subscribed " + descriptor.getCharacteristic().getUuid()
                    + " (status=" + status + ")");
            subscribeNext(gatt);
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt,
                                            @NonNull BluetoothGattCharacteristic characteristic,
                                            @NonNull byte[] value) {
            log("NTFY " + characteristic.getUuid() + " = " + bytesToHex(value));
        }

        // Pre-API-33 callback. Keep for older Android builds.
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;
            log("NTFY " + characteristic.getUuid() + " = " + bytesToHex(value));
        }
    };

    private void log(final String line) {
        final long t = SystemClock.elapsedRealtime() - mStartMs;
        final String stamped = String.format(Locale.ROOT, "[%6d ms] %s", t, line);
        android.util.Log.i(TAG, stamped);
        mDump.append(stamped).append('\n');
        mUi.post(() -> {
            mLog.append(stamped + "\n");
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private static String bytesToHex(@Nullable byte[] bytes) {
        if (bytes == null) return "<null>";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xff));
        }
        return sb.toString().trim();
    }

    private static String propsToString(int p) {
        StringBuilder sb = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) sb.append("BCAST ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("READ ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("WRITE_NR ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("WRITE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("NOTIFY ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("INDICATE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) sb.append("SIGN_WR ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) sb.append("EXT_PROPS ");
        return sb.toString().trim();
    }

    private void saveToFile() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = Environment.getExternalStorageDirectory();
            String name = "poa121-probe-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                    + ".txt";
            File out = new File(dir, name);
            try (FileWriter w = new FileWriter(out)) {
                w.write(mDump.toString());
            }
            Toast.makeText(this, "Saved: " + out.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            log("Saved to " + out.getAbsolutePath());
        } catch (IOException e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        stopProbe(false);
        super.onDestroy();
    }
}
