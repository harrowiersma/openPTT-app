package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Static broadcast receiver for Hytera/Meig PTT hardware button.
 * The P50's ROM intercepts the PTT key at the WindowManager level and sends
 * a broadcast (com.meigsmart.meigkeyaccessibility.onkeyevent) instead of a
 * normal key event. Must be declared in the manifest to receive the implicit
 * broadcast on Android 12+.
 *
 * Extras from the ROM: "action" (0=down, 1=up), "keycode" (142 for PTT).
 */
public class MeigPttReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        int keyAction = extras.getInt("action", -1);

        Intent talkIntent = new Intent(context, MumlaService.class);
        if (keyAction == 0) {
            talkIntent.setAction(MumlaService.ACTION_PTT_DOWN);
        } else if (keyAction == 1) {
            talkIntent.setAction(MumlaService.ACTION_PTT_UP);
        } else {
            return;
        }
        context.startService(talkIntent);
    }
}
