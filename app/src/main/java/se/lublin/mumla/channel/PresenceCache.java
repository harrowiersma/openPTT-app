/*
 * Caches the server-side presence map (status_label per username)
 * for the channel-list filter. Polled every 20 s by MumlaService while
 * Mumble is connected; refreshed immediately on connect and after each
 * successful postStatus. Listeners get a callback when the map content
 * actually changes (cheap ref-equals + Map.equals fast-path).
 *
 * All fields are volatile / read on the UI thread; writes happen on a
 * worker thread inside refresh(). Keep the read API allocation-free.
 */
package se.lublin.mumla.channel;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PresenceCache {

    private static final String TAG = "PresenceCache";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    public interface Listener {
        /** Called on a worker thread whenever the cached map content
         *  changes. Implementations should bounce to the UI thread. */
        void onPresenceMapUpdated();
    }

    private final String mAdminUrl;
    private volatile Map<String, String> mStatusByLcUsername = Collections.emptyMap();
    private final Set<Listener> mListeners =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private final java.util.concurrent.ExecutorService mWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "presence-refresh");
                t.setDaemon(true);
                return t;
            });

    public PresenceCache(String adminUrl) {
        mAdminUrl = adminUrl;
    }

    /** Returns the cached status_label for a user, lowercased lookup.
     *  null if the user is unknown to the cache (treat as visible). */
    public String getStatus(String username) {
        if (username == null) return null;
        return mStatusByLcUsername.get(username.toLowerCase(Locale.ROOT));
    }

    public void addListener(Listener l) { if (l != null) mListeners.add(l); }
    public void removeListener(Listener l) { mListeners.remove(l); }

    /** Background HTTP fetch + cache swap. Safe to call concurrently;
     *  the worst case is two parallel fetches racing to install their
     *  result, both correct. */
    public void refresh() {
        if (mAdminUrl == null || mAdminUrl.isEmpty()) return;
        mWorker.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(
                        mAdminUrl + "/api/users/presence-map").openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                if (conn.getResponseCode() != 200) return;

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[1024];
                int n;
                while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
                JSONObject root = new JSONObject(buf.toString("UTF-8"));

                Map<String, String> next = new HashMap<>(root.length());
                for (java.util.Iterator<String> it = root.keys(); it.hasNext();) {
                    String k = it.next();
                    JSONObject entry = root.optJSONObject(k);
                    if (entry == null) continue;
                    String label = entry.isNull("status_label")
                            ? null : entry.optString("status_label", null);
                    next.put(k, label);  // keys are already lowercase from the server
                }

                Map<String, String> prev = mStatusByLcUsername;
                if (!next.equals(prev)) {
                    mStatusByLcUsername = Collections.unmodifiableMap(next);
                    notifyListeners();
                }
            } catch (Exception e) {
                Log.w(TAG, "presence-map refresh failed: " + e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Shut down the internal worker and drop all listeners. Call from
     *  the owning service's onDestroy() so the executor's daemon thread
     *  doesn't outlive the service. */
    public void close() {
        mWorker.shutdownNow();
        mListeners.clear();
    }

    private void notifyListeners() {
        // Snapshot to avoid CME if a listener removes itself in the callback.
        Listener[] snap;
        synchronized (mListeners) {
            snap = mListeners.toArray(new Listener[0]);
        }
        for (Listener l : snap) {
            try { l.onPresenceMapUpdated(); }
            catch (Exception e) { Log.w(TAG, "listener threw: " + e); }
        }
    }
}
