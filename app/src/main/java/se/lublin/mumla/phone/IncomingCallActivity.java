/*
 * Full-screen overlay raised when the admin whispers an INCOMING_CALL
 * payload ("INCOMING_CALL|<caller>|<sub_channel>"). Plays the device
 * default ringtone while visible, shows the caller ID + destination
 * sub-channel, and gives the operator Answer / Decline.
 *
 * On Answer, we hand off to MumlaService via a named intent action so
 * the move-to-channel logic stays inside the service (it owns the
 * Humla session). On Decline, we just finish — the SIP bridge keeps
 * its slot open for whoever else might answer; our server-side
 * _notify_loop keeps pinging eligible users until someone actually
 * joins the Phone channel tree.
 */

package se.lublin.mumla.phone;

import android.app.Activity;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import se.lublin.mumla.R;
import se.lublin.mumla.service.MumlaService;

public class IncomingCallActivity extends AppCompatActivity {
    private static final String TAG = "IncomingCallActivity";
    public static final String EXTRA_CALLER_ID = "caller_id";
    public static final String EXTRA_SUB_CHANNEL = "sub_channel";

    private String mCallerId;
    private String mSubChannel;
    @Nullable private Ringtone mRingtone;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_incoming_call);

        Intent intent = getIntent();
        mCallerId = intent != null ? intent.getStringExtra(EXTRA_CALLER_ID) : null;
        mSubChannel = intent != null ? intent.getStringExtra(EXTRA_SUB_CHANNEL) : null;
        if (mCallerId == null) mCallerId = "Unknown";
        if (mSubChannel == null) mSubChannel = "";

        TextView caller = findViewById(R.id.incomingCallCallerId);
        caller.setText(mCallerId);

        TextView channel = findViewById(R.id.incomingCallChannel);
        channel.setText(mSubChannel);

        MaterialButton answer = findViewById(R.id.incomingCallAnswer);
        answer.setOnClickListener(v -> onAnswer());

        MaterialButton decline = findViewById(R.id.incomingCallDecline);
        decline.setOnClickListener(v -> finish());

        startRinging();
    }

    private void onAnswer() {
        Log.i(TAG, "Answer tapped — sub=" + mSubChannel + " caller=" + mCallerId);
        // Tell the service to move the session into Phone/Call-N.
        Intent svc = new Intent(this, MumlaService.class);
        svc.setAction(MumlaService.ACTION_MOVE_TO_CHANNEL);
        svc.putExtra(MumlaService.EXTRA_CHANNEL_NAME, mSubChannel);
        svc.putExtra(MumlaService.EXTRA_PARENT_NAME, "Phone");
        svc.putExtra(MumlaService.EXTRA_CALLER_ID, mCallerId);
        try {
            android.content.ComponentName result = startService(svc);
            Log.i(TAG, "Answer: startService returned " + result);
        } catch (Exception e) {
            Log.w(TAG, "Answer: startService threw " + e, e);
        }
        // Launch the active-call overlay here (Activity→Activity is
        // always allowed), not from the service's onUserJoinedChannel —
        // Android 10+ blocks background-start attempts from services
        // so the server-ack callback can't do it reliably. ActiveCall
        // starts "connecting" and its own observer transitions to the
        // live state when Murmur confirms the join.
        Intent call = new Intent(this, ActiveCallActivity.class);
        call.putExtra(ActiveCallActivity.EXTRA_CALLER_ID, mCallerId);
        call.putExtra(ActiveCallActivity.EXTRA_SUB_CHANNEL, mSubChannel);
        startActivity(call);
        finish();
    }

    private void startRinging() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) return;
            mRingtone = RingtoneManager.getRingtone(this, uri);
            if (mRingtone == null) return;
            // Apply the user-configurable ring volume (Settings →
            // Appearance → Ring volume). Ringtone.setVolume requires
            // API 28+; the P50 is well past that.
            try {
                float v = se.lublin.mumla.Settings.getInstance(this)
                        .getIncomingRingVolume();
                mRingtone.setVolume(v);
            } catch (Throwable ignored) { /* older API, fall back to default */ }
            mRingtone.play();
        } catch (Exception e) {
            Log.w(TAG, "ringtone start failed: " + e);
        }
    }

    /**
     * Hardware-key routing. The P50 is touchscreen-less, so the overlay
     * has to be driven by physical buttons — otherwise the operator
     * sees the ring and has no way to answer:
     *
     *   KEYCODE_CALL (green phone button)            → Answer
     *   KEYCODE_DPAD_CENTER / ENTER (OK / D-pad OK)  → Answer (fallback)
     *   KEYCODE_BACK                                 → Decline (via
     *       default Activity finish, but we intercept so the click
     *       hint text on the red button stays accurate if Android
     *       adds back-gesture handling later)
     *   KEYCODE_MENU                                 → Decline
     *
     * We consume the event (return true) so KEYCODE_CALL doesn't
     * bubble up to the system dialer while the overlay is foreground.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware MENU → Decline");
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware BACK → Decline");
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_CALL:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware key → Answer (keyCode=" + keyCode + ")");
                    onAnswer();
                }
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onMenuOpened(int featureId, android.view.Menu menu) {
        return false;
    }

    @Override
    protected void onDestroy() {
        try {
            if (mRingtone != null && mRingtone.isPlaying()) mRingtone.stop();
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
