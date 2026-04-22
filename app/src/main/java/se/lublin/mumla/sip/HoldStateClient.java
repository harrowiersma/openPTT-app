/*
 * Polls /api/sip/hold-state every 5 s while Mumble is connected. Tracks
 * whether THIS device is currently holding a SIP call (operator's slot
 * matches the held-state's slot). Notifies listeners on transitions so
 * the carousel knob-lock and the hold-banner UI repaint immediately.
 *
 * Mirrors PresenceCache in shape. Not coalesced with PresenceCache
 * because the underlying endpoint, polling cadence, and consumer set
 * are different — keep them as siblings, not a god-object.
 */
package se.lublin.mumla.sip;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HoldStateClient {

    private static final String TAG = "HoldStateClient";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    public interface Listener {
        /** Called on a worker thread whenever the holding flag or slot flips. */
        void onHoldStateChanged(boolean holding, int slot);
    }

    private final String mAdminUrl;
    private volatile boolean mHolding = false;
    private volatile int mSlot = 0;
    private final Set<Listener> mListeners =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hold-state-refresh");
        t.setDaemon(true);
        return t;
    });

    public HoldStateClient(String adminUrl) {
        mAdminUrl = adminUrl;
    }

    public boolean isHolding() { return mHolding; }
    public int getSlot()       { return mSlot; }

    public void addListener(Listener l) { if (l != null) mListeners.add(l); }
    public void removeListener(Listener l) { mListeners.remove(l); }

    public void refresh() {
        if (mAdminUrl == null || mAdminUrl.isEmpty()) return;
        mWorker.execute(this::doRefresh);
    }

    public void close() {
        mWorker.shutdownNow();
        mListeners.clear();
    }

    private void doRefresh() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    mAdminUrl + "/api/sip/hold-state").openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) return;

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[256]; int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            JSONObject root = new JSONObject(buf.toString("UTF-8"));

            boolean nextHolding = root.optBoolean("holding", false);
            int nextSlot = root.optInt("slot", 0);

            if (nextHolding != mHolding || nextSlot != mSlot) {
                mHolding = nextHolding;
                mSlot = nextSlot;
                notifyListeners();
            }
        } catch (Exception e) {
            Log.w(TAG, "hold-state refresh failed: " + e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void notifyListeners() {
        Listener[] snap;
        synchronized (mListeners) {
            snap = mListeners.toArray(new Listener[0]);
        }
        for (Listener l : snap) {
            try { l.onHoldStateChanged(mHolding, mSlot); }
            catch (Exception e) { Log.w(TAG, "listener threw: " + e); }
        }
    }
}
