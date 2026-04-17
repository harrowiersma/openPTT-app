package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Static broadcast receiver for Hytera/Meig hardware buttons.
 * The P50's ROM intercepts hardware keys at the WindowManager level and
 * forwards them as broadcasts (com.meigsmart.meigkeyaccessibility.onkeyevent)
 * instead of normal key events. Must be declared in the manifest to receive
 * the implicit broadcast on Android 12+.
 *
 * Extras observed from the ROM: "action" (0=down, 1=up), "keycode".
 * Keycodes:
 *   142 = side PTT
 *   133 = KEY_F3 (upper side function key)
 *   134 = KEY_F4 (lower side function key)
 */
public class MeigPttReceiver extends BroadcastReceiver {
    private static final String TAG = "MeigPttReceiver";

    private static final int KEYCODE_PTT = 142;
    private static final int KEYCODE_F3 = 133;

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        int keyAction = extras.getInt("action", -1);
        int keycode = extras.getInt("keycode", -1);
        // Useful during hardware bring-up; silent in release logs.
        Log.d(TAG, "meig key event: keycode=" + keycode + " action=" + keyAction);

        String action = null;
        if (keycode == KEYCODE_PTT) {
            if (keyAction == 0) action = MumlaService.ACTION_PTT_DOWN;
            else if (keyAction == 1) action = MumlaService.ACTION_PTT_UP;
        } else if (keycode == KEYCODE_F3) {
            if (keyAction == 0) action = MumlaService.ACTION_SHIFT_KEY_DOWN;
            else if (keyAction == 1) action = MumlaService.ACTION_SHIFT_KEY_UP;
        } else if (keycode == -1) {
            // Legacy broadcast with no keycode extra — assume PTT for backwards
            // compatibility with earlier ROMs we tested against.
            if (keyAction == 0) action = MumlaService.ACTION_PTT_DOWN;
            else if (keyAction == 1) action = MumlaService.ACTION_PTT_UP;
        }

        if (action == null) return;
        Intent svc = new Intent(context, MumlaService.class);
        svc.setAction(action);
        context.startService(svc);
    }
}
