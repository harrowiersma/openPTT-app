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
        // Hand off to the service — it owns the Humla session and can
        // resolve "Call-N under Phone" into a channel id for joinChannel.
        Intent svc = new Intent(this, MumlaService.class);
        svc.setAction(MumlaService.ACTION_MOVE_TO_CHANNEL);
        svc.putExtra(MumlaService.EXTRA_CHANNEL_NAME, mSubChannel);
        svc.putExtra(MumlaService.EXTRA_PARENT_NAME, "Phone");
        startService(svc);
        finish();
    }

    private void startRinging() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) return;
            mRingtone = RingtoneManager.getRingtone(this, uri);
            if (mRingtone != null) mRingtone.play();
        } catch (Exception e) {
            Log.w(TAG, "ringtone start failed: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (mRingtone != null && mRingtone.isPlaying()) mRingtone.stop();
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
