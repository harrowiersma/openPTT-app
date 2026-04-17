/*
 * Lone-worker shift control driven by a long-press of a hardware key.
 *
 * Coordinates three things:
 *   1. Timing: only act on a KEYCODE_F3 press held >= LONG_PRESS_MS.
 *   2. HTTP: POST /api/loneworker/shift/{start,stop} to the admin API.
 *   3. Speech: Android TextToSpeech confirms state transitions locally
 *      so the user doesn't need to look at the 240x320 screen.
 *
 * State machine:
 *   IDLE --long-press--> ACTIVE  (server issued a shift; TTS "shift started")
 *   ACTIVE --long-press--> ENDING (TTS "ending shift, long press again to cancel")
 *   ENDING --long-press within GRACE_MS--> ACTIVE (cancelled)
 *   ENDING --GRACE_MS elapses--> IDLE  (server stops the shift; TTS "shift ended")
 */

package se.lublin.mumla.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class ShiftController {
    private static final String TAG = "ShiftController";
    private static final long LONG_PRESS_MS = 1500;
    private static final long GRACE_MS = 3000;

    private enum State { IDLE, ACTIVE, ENDING }

    private final Context mContext;
    private final Settings mSettings;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mHttp = Executors.newSingleThreadExecutor();

    private TextToSpeech mTts;
    private boolean mTtsReady = false;
    private State mState = State.IDLE;
    private long mKeyDownTime = 0;
    private Runnable mGraceTimeout;

    public ShiftController(Context context, Settings settings) {
        mContext = context.getApplicationContext();
        mSettings = settings;
        mTts = new TextToSpeech(mContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTts.setLanguage(Locale.US);
                mTtsReady = true;
            } else {
                Log.w(TAG, "TTS init failed: " + status);
            }
        });
    }

    public void shutdown() {
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
        mHttp.shutdown();
    }

    /** Called on ACTION_DOWN. Records the press start time. */
    public void onKeyDown() {
        if (mKeyDownTime == 0) {
            mKeyDownTime = SystemClock.uptimeMillis();
        }
    }

    /**
     * Called on ACTION_UP. If the press qualified as a long press,
     * advance the state machine.
     */
    public void onKeyUp(String username) {
        long downTime = mKeyDownTime;
        mKeyDownTime = 0;
        if (downTime == 0) return;

        long held = SystemClock.uptimeMillis() - downTime;
        if (held < LONG_PRESS_MS) return;
        advanceState(username);
    }

    /**
     * Trigger a shift toggle directly, without any press-timing. Used by
     * alternative gestures (e.g. triple-tap PTT) where timing logic lives
     * elsewhere.
     */
    public void toggle(String username) {
        advanceState(username);
    }

    private void advanceState(String username) {
        if (username == null || username.isEmpty()) {
            Log.w(TAG, "no username available; shift toggle ignored");
            return;
        }

        String adminUrl = mSettings.getAdminUrl();
        if (adminUrl == null || adminUrl.isEmpty()) {
            Log.w(TAG, "admin URL not configured; shift toggle ignored");
            return;
        }

        switch (mState) {
            case IDLE:
                startShift(adminUrl, username);
                break;
            case ACTIVE:
                beginEndGrace(adminUrl, username);
                break;
            case ENDING:
                cancelEnd();
                break;
        }
    }

    private void startShift(String adminUrl, String username) {
        speak(mContext.getString(R.string.shift_started_tts, "..."));
        mHttp.execute(() -> {
            String body = postJson(adminUrl + "/api/loneworker/shift/start",
                    "{\"username\":\"" + jsonEscape(username) + "\"}");
            if (body == null) {
                mMainHandler.post(() -> speak(mContext.getString(R.string.shift_start_failed_tts)));
                return;
            }
            String endAt = extractTime(body, "planned_end_at");
            mMainHandler.post(() -> {
                mState = State.ACTIVE;
                if (endAt != null) {
                    speak(mContext.getString(R.string.shift_started_tts, endAt));
                }
            });
        });
    }

    private void beginEndGrace(String adminUrl, String username) {
        mState = State.ENDING;
        speak(mContext.getString(R.string.shift_stopping_tts));
        mGraceTimeout = () -> commitEnd(adminUrl, username);
        mMainHandler.postDelayed(mGraceTimeout, GRACE_MS);
    }

    private void cancelEnd() {
        if (mGraceTimeout != null) {
            mMainHandler.removeCallbacks(mGraceTimeout);
            mGraceTimeout = null;
        }
        mState = State.ACTIVE;
        speak("Shift end cancelled.");
    }

    private void commitEnd(String adminUrl, String username) {
        mGraceTimeout = null;
        mHttp.execute(() -> {
            postJson(adminUrl + "/api/loneworker/shift/stop",
                    "{\"username\":\"" + jsonEscape(username) + "\"}");
            mMainHandler.post(() -> {
                mState = State.IDLE;
                speak(mContext.getString(R.string.shift_stopped_tts));
            });
        });
    }

    private void speak(String text) {
        if (mTts != null && mTtsReady) {
            mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shift");
        } else {
            Log.i(TAG, "TTS not ready: " + text);
        }
    }

    private String postJson(String url, String json) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    return sb.toString();
                }
            }
            Log.w(TAG, "POST " + url + " -> " + code);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "POST " + url + " failed", e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Tiny, forgiving time extractor: finds the "HH:MM" portion of an ISO
     * timestamp field without pulling in a JSON parser. Returns null if the
     * field isn't present.
     */
    private static String extractTime(String jsonBody, String field) {
        int keyIdx = jsonBody.indexOf("\"" + field + "\":");
        if (keyIdx < 0) return null;
        int colon = jsonBody.indexOf(':', keyIdx);
        int quote = jsonBody.indexOf('"', colon);
        if (quote < 0) return null;
        int end = jsonBody.indexOf('"', quote + 1);
        if (end < 0) return null;
        String iso = jsonBody.substring(quote + 1, end);
        // iso is e.g. 2026-04-17T13:18:51.861252+00:00
        int tIdx = iso.indexOf('T');
        if (tIdx < 0 || iso.length() < tIdx + 6) return iso;
        return iso.substring(tIdx + 1, tIdx + 6);
    }
}
