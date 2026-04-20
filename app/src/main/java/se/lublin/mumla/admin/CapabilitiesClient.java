/*
 * Fetches /api/status/capabilities from the admin server and caches the
 * result in SharedPreferences. Call refresh() on service start; read via
 * hasFeature() from anywhere in the app.
 *
 * Cache survives restart so feature-gated UI renders immediately even
 * before the first network round-trip completes. The SharedPreferences
 * Editor.apply() is thread-safe, so refresh() is safe to call from any
 * background thread.
 */

package se.lublin.mumla.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class CapabilitiesClient {
    private static final String TAG = "CapabilitiesClient";
    private static final String PREFS = "openptt_capabilities";
    private static final String KEY_SERVER_VERSION = "__server_version";
    private static final String KEY_FETCHED_AT = "__fetched_at";

    private final SharedPreferences mPrefs;

    public CapabilitiesClient(Context ctx) {
        mPrefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Fetch capabilities from the given admin base URL synchronously.
     * Call from a background thread — this blocks on HTTP.
     * Returns true on success, false on any error (cache preserved).
     */
    public boolean refresh(String adminBaseUrl) {
        if (adminBaseUrl == null || adminBaseUrl.isEmpty()) return false;
        String url = adminBaseUrl.replaceAll("/+$", "") + "/api/status/capabilities";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "capabilities HTTP " + code);
                return false;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject features = root.optJSONObject("features");
            if (features == null) {
                Log.w(TAG, "capabilities response missing 'features' object");
                return false;
            }
            SharedPreferences.Editor e = mPrefs.edit();
            e.clear();
            Iterator<String> it = features.keys();
            while (it.hasNext()) {
                String key = it.next();
                e.putBoolean(key, features.optBoolean(key, false));
            }
            e.putString(KEY_SERVER_VERSION, root.optString("server_version", "unknown"));
            e.putLong(KEY_FETCHED_AT, System.currentTimeMillis());
            e.apply();
            Log.i(TAG, "capabilities refreshed: " + features);
            return true;
        } catch (Exception ex) {
            Log.w(TAG, "capabilities fetch failed: " + ex.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Return true if the named feature is enabled. Defaults to TRUE when
     * the cache has no entry — fail-open so first-launch (before the
     * first refresh completes) doesn't cripple every feature.
     */
    public boolean hasFeature(String key) {
        return mPrefs.getBoolean(key, true);
    }

    public String serverVersion() {
        return mPrefs.getString(KEY_SERVER_VERSION, "unknown");
    }

    public long fetchedAtMs() {
        return mPrefs.getLong(KEY_FETCHED_AT, 0L);
    }
}
