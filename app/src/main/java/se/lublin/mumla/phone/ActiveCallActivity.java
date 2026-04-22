/*
 * Full-screen in-call view. Raised by MumlaService the moment the
 * session user enters Phone/Call-<slot>, dismissed the moment the
 * session leaves. The operator gets clear visual feedback that the
 * call is live, plus a hangup prompt bound to the hardware MENU key.
 *
 * The Activity owns its own HumlaObserver subscription so it can
 * watch for the session-user leaving the Call-* sub-channel and
 * finish itself without needing the service to explicitly tear it
 * down. This also handles the case where the operator hangs up by
 * pressing MENU in MumlaActivity — Murmur moves them out of Call-N,
 * we observe, and we close.
 */

package se.lublin.mumla.phone;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.service.MumlaService;

public class ActiveCallActivity extends AppCompatActivity {
    private static final String TAG = "ActiveCallActivity";
    public static final String EXTRA_CALLER_ID = "caller_id";
    public static final String EXTRA_SUB_CHANNEL = "sub_channel";

    private String mCallerId;
    private String mSubChannel;
    private long mCallStartMs;
    private TextView mDurationView;
    private Handler mTickHandler;

    /**
     * Bound service that lets us observe Humla events and call hangup.
     * We piggyback on the existing HumlaServiceFragment binder pattern
     * by wrapping a minimal connection here rather than extending the
     * fragment infrastructure into a second Activity.
     */
    @Nullable private MumlaService mService;
    private android.content.ServiceConnection mConn;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            updateDurationText();
            if (mTickHandler != null) mTickHandler.postDelayed(this, 500);
        }
    };

    private final IHumlaObserver mObserver = new HumlaObserver() {
        @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            if (mService == null) return;
            try {
                int selfSession = mService.HumlaSession().getSessionId();
                if (user != null && user.getSession() == selfSession) {
                    String name = newChannel == null ? null : newChannel.getName();
                    if (name == null || !name.startsWith("Call-")) {
                        Log.i(TAG, "session left Call-* — finishing");
                        finish();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "onUserJoinedChannel: " + e);
            }
        }
        @Override public void onDisconnected(HumlaException e) {
            Log.i(TAG, "Humla disconnected — finishing");
            finish();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_active_call);

        Intent intent = getIntent();
        mCallerId = intent != null ? intent.getStringExtra(EXTRA_CALLER_ID) : null;
        mSubChannel = intent != null ? intent.getStringExtra(EXTRA_SUB_CHANNEL) : null;
        if (mCallerId == null || mCallerId.isEmpty()) mCallerId = "Unknown";
        if (mSubChannel == null) mSubChannel = "";

        ((TextView) findViewById(R.id.activeCallCallerId)).setText(mCallerId);
        ((TextView) findViewById(R.id.activeCallChannel)).setText(mSubChannel);
        mDurationView = findViewById(R.id.activeCallDuration);
        MaterialButton hangup = findViewById(R.id.activeCallHangup);
        hangup.setOnClickListener(v -> triggerHangup());

        mCallStartMs = SystemClock.elapsedRealtime();
        mTickHandler = new Handler(Looper.getMainLooper());
        mTickHandler.post(mTick);

        bindToService();
    }

    @Override
    protected void onDestroy() {
        if (mTickHandler != null) {
            mTickHandler.removeCallbacks(mTick);
            mTickHandler = null;
        }
        unbindFromService();
        super.onDestroy();
    }

    /** Hardware-key routing. Fire the action on ACTION_UP (Android
     *  convention, and crucially: so finish() runs AFTER we've already
     *  consumed the UP — otherwise finish() on DOWN tears the Activity
     *  down before UP arrives, UP bubbles to MumlaActivity, and its
     *  options menu opens over the carousel). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware MENU → Hangup");
                    triggerHangup();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware BACK → Hangup");
                    triggerHangup();
                }
                return true;
            case KeyEvent.KEYCODE_CALL:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware CALL → Hold-toggle");
                    if (mService != null) mService.phoneHoldToggle();
                }
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Belt-and-braces: even if a key slips through dispatchKeyEvent,
     *  refuse to open an options menu over this screen. */
    @Override
    public boolean onMenuOpened(int featureId, android.view.Menu menu) {
        return false;
    }

    private void triggerHangup() {
        if (mService != null) {
            // Fire the hangup RPC (SIP bridge tears down the call) and
            // restore the operator to their pre-call channel so they
            // aren't left sitting in the empty Call-N. Both are
            // fire-and-forget — they'll race but that's fine; Murmur
            // handles either ordering.
            mService.phoneHangup();
            mService.restorePreCallChannel();
        }
        finish();
    }

    private void updateDurationText() {
        if (mDurationView == null) return;
        long elapsed = (SystemClock.elapsedRealtime() - mCallStartMs) / 1000;
        long m = elapsed / 60;
        long s = elapsed % 60;
        mDurationView.setText(String.format(java.util.Locale.US, "%02d:%02d", m, s));
    }

    private void bindToService() {
        mConn = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name,
                                                     android.os.IBinder binder) {
                try {
                    MumlaService.MumlaBinder mb = (MumlaService.MumlaBinder) binder;
                    mService = (MumlaService) mb.getService();
                    mService.registerObserver(mObserver);
                } catch (Exception e) {
                    Log.w(TAG, "bind failed: " + e);
                }
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {
                mService = null;
            }
        };
        bindService(new Intent(this, MumlaService.class), mConn,
                android.content.Context.BIND_AUTO_CREATE);
    }

    private void unbindFromService() {
        if (mService != null) {
            try { mService.unregisterObserver(mObserver); } catch (Exception ignored) { }
        }
        if (mConn != null) {
            try { unbindService(mConn); } catch (Exception ignored) { }
            mConn = null;
        }
    }
}
