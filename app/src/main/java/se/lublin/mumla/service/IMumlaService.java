package se.lublin.mumla.service;

import java.util.List;

import se.lublin.humla.IHumlaService;
import se.lublin.mumla.channel.PresenceCache;

/**
 * Created by andrew on 28/02/17.
 */
public interface IMumlaService extends IHumlaService {
    void setOverlayShown(boolean showOverlay);

    boolean isOverlayShown();

    void clearChatNotifications();

    void markErrorShown();

    boolean isErrorShown();

    void onTalkKeyDown();

    void onTalkKeyUp();

    List<IChatMessage> getMessageLog();

    void clearMessageLog();

    void setSuppressNotifications(boolean suppressNotifications);

    /**
     * Switch to the next or previous channel using the rotary knob.
     * @param direction +1 for next (F6/clockwise), -1 for previous (F5/counter-clockwise)
     */
    void switchChannel(int direction);

    String currentChannelName();

    void phoneMuteToggle();

    void phoneHangup();

    /**
     * Return true if the given feature flag (as published by the admin
     * server at /api/status/capabilities) is enabled. Defaults to true
     * when the cache has never been populated, so a first launch before
     * the initial refresh completes is never crippled.
     *
     * @param key feature key, e.g. "lone_worker", "sip", "dispatch",
     *            "weather", "sos"
     */
    boolean hasFeature(String key);

    /** Local user's Mumble username if connected; null otherwise. */
    String getOwnUsername();

    /** Last presence label confirmed by /api/users/status this session. */
    String getCurrentStatus();

    /** Last audibility flag confirmed by /api/users/status this session. */
    Boolean getCurrentAudible();

    /** Compute current audibility from AudioManager (RINGER_MODE_NORMAL
     *  AND voice-call stream volume > 0). */
    boolean computeAudible();

    /** POST /api/users/status with label and/or audibility. Best-effort,
     *  fires on a background thread. Callbacks run on worker thread. */
    void postStatus(String label, Boolean isAudible, Runnable onSuccess, Runnable onError);

    /** GET /api/users/status for the current Mumble user — populates the
     *  cached label + audibility. onDone runs on the worker thread once
     *  the request finishes (success or failure). UI callers must hop
     *  to the main thread themselves. */
    void fetchStatus(Runnable onDone);

    /** Public TTS passthrough so UI callers can confirm status changes. */
    void speakNow(String text);

    /** Channel-list presence filter cache, refreshed every 20 s while
     *  Mumble is connected. May be null before onCreate completes. */
    PresenceCache getPresenceCache();
}
