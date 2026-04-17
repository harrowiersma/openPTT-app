/*
 * Starts openPTT TRX on device boot so the dispatch radio auto-reconnects
 * without user intervention. The MumlaActivity itself handles auto-connect
 * to the first favourite when Settings.isAutoConnectEnabled() is true.
 */

package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import se.lublin.mumla.Settings;
import se.lublin.mumla.app.MumlaActivity;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Settings settings = Settings.getInstance(context);
        if (!settings.isAutoConnectEnabled()) {
            Log.i(TAG, "Boot received but auto-connect disabled — skipping");
            return;
        }
        Log.i(TAG, "Boot received — launching MumlaActivity for auto-connect");
        Intent launch = new Intent(context, MumlaActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }
}
