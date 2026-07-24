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
import android.os.Handler;
import android.os.Looper;
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

    /** Maximum ring time before we auto-dismiss. Matches standard phone
     *  behaviour (missed call) and prevents a stale INCOMING_CALL whisper
     *  — e.g. one delivered late after a server outage recovery — from
     *  leaving an unstoppable ringtone playing until the operator finds
     *  the radio. Hardware buttons still dismiss earlier. */
    private static final long RING_TIMEOUT_MS = 45_000L;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRingTimeout = () -> {
        Log.i(TAG, "ring timeout — dismissing (no answer in "
                + (RING_TIMEOUT_MS / 1000) + "s)");
        if (!isFinishing()) finish();
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_incoming_call);

        MaterialButton answer = findViewById(R.id.incomingCallAnswer);
        answer.setOnClickListener(v -> onAnswer());

        MaterialButton decline = findViewById(R.id.incomingCallDecline);
        decline.setOnClickListener(v -> finish());

        applyCallIntent(getIntent());
        startRinging();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // singleTask launch mode means a second INCOMING_CALL whisper
        // arriving while this activity is on-screen re-uses the same
        // task; without this override, the new caller-id/sub-channel
        // extras would be dropped and the operator could answer into
        // the wrong SIP leg. Refresh the visible extras and re-arm the
        // ring watchdog so the fresher call gets its full 45 s.
        super.onNewIntent(intent);
        setIntent(intent);
        applyCallIntent(intent);
        // startRinging removes+re-posts the watchdog and re-plays the
        // ringtone, so a second call gets a fresh 45 s + a new audible
        // cue rather than sharing the previous call's timer.
        try {
            if (mRingtone != null && mRingtone.isPlaying()) mRingtone.stop();
        } catch (Exception ignored) { }
        startRinging();
    }

    private void applyCallIntent(@Nullable Intent intent) {
        mCallerId = intent != null ? intent.getStringExtra(EXTRA_CALLER_ID) : null;
        mSubChannel = intent != null ? intent.getStringExtra(EXTRA_SUB_CHANNEL) : null;
        if (mCallerId == null) mCallerId = "Unknown";
        if (mSubChannel == null) mSubChannel = "";
        TextView caller = findViewById(R.id.incomingCallCallerId);
        if (caller != null) caller.setText(mCallerId);
        TextView channel = findViewById(R.id.incomingCallChannel);
        if (channel != null) channel.setText(mSubChannel);
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
        // Arm the auto-dismiss watchdog even if the ringtone itself
        // failed to play — the visible overlay alone is still a
        // distraction that needs to time out. Idempotent: onNewIntent
        // calls startRinging again for a second incoming call, and the
        // remove-first ensures we don't stack two watchdogs on one
        // activity instance.
        mMainHandler.removeCallbacks(mRingTimeout);
        mMainHandler.postDelayed(mRingTimeout, RING_TIMEOUT_MS);
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
     *   KEYCODE_POWER (red button on P50; physically the same key as
     *       power)                                   → Decline
     *       The OS-level PhoneWindowManager still turns the screen off
     *       in parallel — we can't suppress that from dispatchKeyEvent
     *       — but the reject fires first. Accepted UX tradeoff: mirrors
     *       the "hang up handset → phone at rest" metaphor.
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
            case KeyEvent.KEYCODE_POWER:
                // Red button on P50 (same physical key as power). Only
                // scoped to this activity — when the ring isn't active,
                // power behaves normally for screen-off.
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    Log.i(TAG, "hardware POWER → Decline");
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
        mMainHandler.removeCallbacks(mRingTimeout);
        try {
            if (mRingtone != null && mRingtone.isPlaying()) mRingtone.stop();
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
