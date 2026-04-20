/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.admin.CapabilitiesClient;
import se.lublin.mumla.phone.IncomingCallActivity;
import se.lublin.mumla.phone.IncomingCallParser;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    public static final int RECONNECT_DELAY = 10000;

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Reports GPS + battery to Traccar while connected. */
    private LocationReporter mLocationReporter;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /** SoundPool for custom notification sounds */
    private SoundPool mSoundPool;
    private int mSoundConnect;
    private int mSoundChannelChange;
    private boolean mSoundPoolReady;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private TextToSpeech mTTS;
    /** True once the TTS engine has finished binding and speak() is usable. */
    private boolean mTTSReady;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if (status == TextToSpeech.ERROR) {
                logWarning(getString(R.string.tts_failed));
                mTTSReady = false;
            } else {
                mTTSReady = true;
            }
        }
    };

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting) + tor,
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }
            if (mSettings.isNotificationSoundsEnabled() && mSoundPoolReady) {
                mSoundPool.play(mSoundConnect, 0.3f, 0.3f, 1, 0, 1f);
            }
        }

        @Override
        public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            try {
                if (!isConnectionEstablished() || user.getSession() != getSessionId()) {
                    return;
                }
                String channelName = newChannel != null ? newChannel.getName() : null;
                boolean deafened = getSessionUser() != null
                        && getSessionUser().isSelfDeafened();

                if (mSettings.isTextToSpeechEnabled() && mTTS != null && mTTSReady
                        && channelName != null && !deafened) {
                    // QUEUE_FLUSH so rapid knob turns only announce the final channel
                    mTTS.speak(channelName, TextToSpeech.QUEUE_FLUSH, null, "channel_change");
                } else if (mSettings.isNotificationSoundsEnabled() && mSoundPoolReady) {
                    // Fallback beep when TTS is off or not yet initialised
                    mSoundPool.play(mSoundChannelChange, 0.3f, 0.3f, 1, 0, 1f);
                }
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserJoinedChannel: " + e);
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null &&
                    user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) {
                return;
            }

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened()); // Update settings mute/deafen state
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            // Intercept the admin's INCOMING_CALL whisper BEFORE any
            // rendering — raise the full-screen overlay and swallow the
            // payload so it never hits TTS, chat notifications, or the
            // chat log. Gated on hasFeature("sip") so a disabled-SIP
            // fleet simply drops the whisper.
            String rawMessage = message.getMessage() == null ? "" : message.getMessage();
            if (IncomingCallParser.looksLike(Jsoup.parseBodyFragment(rawMessage).text())
                    && hasFeature("sip")) {
                IncomingCallParser.Result call =
                        IncomingCallParser.parse(Jsoup.parseBodyFragment(rawMessage).text());
                if (call != null) {
                    Intent i = new Intent(MumlaService.this, IncomingCallActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.putExtra(IncomingCallActivity.EXTRA_CALLER_ID, call.callerId);
                    i.putExtra(IncomingCallActivity.EXTRA_SUB_CHANNEL, call.subChannel);
                    startActivity(i);
                    return;
                }
            }

            // Split on / strip all HTML tags.
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    String href = anchor.attr("href");
                    // Only shorten anchors without custom text
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
            }

            // TODO: create a customizable notification sieve
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            if (isConnectionEstablished() &&
                    user.getSession() == selfSession &&
                    getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK) {
                if (user.getTalkState() == TalkState.TALKING && mPTTSoundEnabled) {
                    AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
                }
            }
        }
    };

    public static final String ACTION_PTT_DOWN = "se.lublin.mumla.PTT_DOWN";
    public static final String ACTION_PTT_UP = "se.lublin.mumla.PTT_UP";
    public static final String ACTION_SHIFT_KEY_DOWN = "se.lublin.mumla.SHIFT_KEY_DOWN";
    public static final String ACTION_SHIFT_KEY_UP = "se.lublin.mumla.SHIFT_KEY_UP";
    /** Fired by IncomingCallActivity when the operator taps Answer. */
    public static final String ACTION_MOVE_TO_CHANNEL = "se.lublin.mumla.MOVE_TO_CHANNEL";
    public static final String EXTRA_CHANNEL_NAME = "channel_name";
    public static final String EXTRA_PARENT_NAME = "parent_name";

    private ShiftController mShiftController;

    /** Feature-flag cache mirrored from /api/status/capabilities. */
    private CapabilitiesClient mCapabilities;
    /** Re-fetches capabilities every 10 min so toggles made while the
     *  app is running propagate without a restart. */
    private Handler mCapabilitiesHandler;
    private static final long CAPABILITIES_FIRST_DELAY_MS = 30_000L;
    private static final long CAPABILITIES_INTERVAL_MS = 10L * 60L * 1000L;
    private final Runnable mCapabilitiesTick = new Runnable() {
        @Override public void run() {
            refreshCapabilitiesInBackground();
            if (mCapabilitiesHandler != null) {
                mCapabilitiesHandler.postDelayed(this, CAPABILITIES_INTERVAL_MS);
            }
        }
    };


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PTT_DOWN.equals(action)) {
                Log.i(TAG, "PTT_DOWN received via intent");
                onTalkKeyDown();
                return START_NOT_STICKY;
            } else if (ACTION_PTT_UP.equals(action)) {
                Log.i(TAG, "PTT_UP received via intent");
                onTalkKeyUp();
                return START_NOT_STICKY;
            } else if (ACTION_SHIFT_KEY_DOWN.equals(action)) {
                Log.i(TAG, "SHIFT_KEY_DOWN received via intent");
                if (hasFeature("lone_worker")) {
                    shiftController().onKeyDown();
                }
                return START_NOT_STICKY;
            } else if (ACTION_SHIFT_KEY_UP.equals(action)) {
                Log.i(TAG, "SHIFT_KEY_UP received via intent");
                if (hasFeature("lone_worker")) {
                    shiftController().onKeyUp(currentMumbleUsername());
                }
                return START_NOT_STICKY;
            } else if (ACTION_MOVE_TO_CHANNEL.equals(action)) {
                String name = intent.getStringExtra(EXTRA_CHANNEL_NAME);
                String parentName = intent.getStringExtra(EXTRA_PARENT_NAME);
                Log.i(TAG, "MOVE_TO_CHANNEL: " + parentName + "/" + name);
                moveSessionToNamedChannel(parentName, name);
                return START_NOT_STICKY;
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private ShiftController shiftController() {
        if (mShiftController == null) {
            mShiftController = new ShiftController(this, mSettings);
        }
        return mShiftController;
    }

    /** Force ShiftController + its TTS engine to initialize now so the first
     * triple-tap isn't silent. Called from onCreate. */
    private void prewarmShiftController() {
        shiftController();
    }

    private String currentMumbleUsername() {
        try {
            if (isConnected() && getSessionUser() != null) {
                return getSessionUser().getName();
            }
        } catch (Exception e) {
            Log.w(TAG, "currentMumbleUsername failed", e);
        }
        return null;
    }

    private String currentMumbleChannelName() {
        try {
            if (isConnected() && getSessionChannel() != null) {
                return getSessionChannel().getName();
            }
        } catch (Exception e) {
            Log.w(TAG, "currentMumbleChannelName failed", e);
        }
        return null;
    }

    /** Public wrapper — MumlaActivity needs this for its KEYCODE_CALL /
     *  KEYCODE_MENU gate (only act when in the Phone channel). */
    public String currentChannelName() {
        return currentMumbleChannelName();
    }

    /** Green-button (KEYCODE_CALL) handler: toggle caller-mute via admin.
     *  Server signals sip-bridge with SIGUSR2; caller hears silence until
     *  toggled again. TTS locally so the user knows the state changed.
     */
    public void phoneMuteToggle() {
        String adminUrl = mSettings.getAdminUrl();
        if (adminUrl == null || adminUrl.isEmpty()) {
            Log.w(TAG, "phoneMuteToggle: admin URL not configured");
            return;
        }
        String username = currentMumbleUsername();
        if (username == null) return;
        speak(getString(R.string.phone_mute_toggled_tts));
        new Thread(() -> _postPhoneControl(adminUrl + "/api/sip/mute-toggle", username)).start();
    }

    /** MENU key handler: hang up the active phone call.
     *  Server signals sip-bridge with SIGUSR1. */
    public void phoneHangup() {
        String adminUrl = mSettings.getAdminUrl();
        if (adminUrl == null || adminUrl.isEmpty()) {
            Log.w(TAG, "phoneHangup: admin URL not configured");
            return;
        }
        String username = currentMumbleUsername();
        if (username == null) return;
        speak(getString(R.string.phone_hung_up_tts));
        new Thread(() -> _postPhoneControl(adminUrl + "/api/sip/hangup-current", username)).start();
    }

    private void _postPhoneControl(String url, String username) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            String body = "{\"username\":\"" + username.replace("\"", "\\\"") + "\"}";
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            Log.i(TAG, "phone control " + url + " → " + code);
        } catch (Exception e) {
            Log.w(TAG, "phone control post failed for " + url, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Resolve "parentName/childName" (e.g. "Phone/Call-1") to an
     *  IChannel and ask Humla to join it. Called by
     *  IncomingCallActivity when the operator taps Answer. */
    private void moveSessionToNamedChannel(String parentName, String childName) {
        if (childName == null || childName.isEmpty()) return;
        try {
            if (!isConnectionEstablished()) return;
            se.lublin.humla.IHumlaSession session = HumlaSession();
            if (session == null) return;
            se.lublin.humla.model.IChannel target =
                    findChildByName(session.getRootChannel(), parentName, childName);
            if (target == null) {
                Log.w(TAG, "move-to-channel: no match for "
                        + parentName + "/" + childName);
                return;
            }
            session.joinChannel(target.getId());
            Log.i(TAG, "moved session to " + parentName + "/" + childName
                    + " (id=" + target.getId() + ")");
        } catch (Exception e) {
            Log.w(TAG, "moveSessionToNamedChannel failed: " + e);
        }
    }

    /** Walk the channel tree looking for a child named childName directly
     *  under a channel named parentName. parentName may be null — then
     *  we fall back to any channel named childName anywhere in the tree. */
    private static se.lublin.humla.model.IChannel findChildByName(
            se.lublin.humla.model.IChannel node, String parentName, String childName) {
        if (node == null) return null;
        java.util.List<? extends se.lublin.humla.model.IChannel> subs = node.getSubchannels();
        if (subs == null) return null;
        // Prefer the constrained match: childName under a channel whose
        // name equals parentName — avoids ambiguity if multiple parents
        // happen to contain the same child name.
        if (parentName != null && parentName.equals(node.getName())) {
            for (se.lublin.humla.model.IChannel c : subs) {
                if (childName.equals(c.getName())) return c;
            }
        }
        for (se.lublin.humla.model.IChannel c : subs) {
            se.lublin.humla.model.IChannel hit = findChildByName(c, parentName, childName);
            if (hit != null) return hit;
        }
        // Fallback: parent constraint failed, but maybe the child exists
        // by itself somewhere — only used if parentName is null/unknown.
        if (parentName == null) {
            for (se.lublin.humla.model.IChannel c : subs) {
                if (childName.equals(c.getName())) return c;
            }
        }
        return null;
    }

    /** Kick a background thread to re-pull /api/status/capabilities from
     *  the admin URL. Safe to call repeatedly; silently no-ops if the
     *  admin URL isn't configured yet. */
    private void refreshCapabilitiesInBackground() {
        if (mCapabilities == null || mSettings == null) return;
        final String adminUrl = mSettings.getAdminUrl();
        if (adminUrl == null || adminUrl.isEmpty()) return;
        new Thread(() -> mCapabilities.refresh(adminUrl),
                "capabilities-refresh").start();
    }

    /** IMumlaService — feature-flag gate readable from anywhere the
     *  service binder is exposed (Activities, Receivers, etc.). */
    @Override
    public boolean hasFeature(String key) {
        return mCapabilities == null || mCapabilities.hasFeature(key);
    }

    /** Short TTS utterance for confirmations. Respects isTextToSpeechEnabled;
     *  no-ops if the engine isn't ready yet. */
    private void speak(String text) {
        if (mTTS != null && mTTSReady && mSettings.isTextToSpeechEnabled()) {
            mTTS.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phone_ctrl");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerObserver(mObserver);

        // Register for preference changes
        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla);

        // Initialize SoundPool for custom sounds
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        mSoundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttrs)
                .build();
        mSoundConnect = mSoundPool.load(this, R.raw.connect, 1);
        mSoundChannelChange = mSoundPool.load(this, R.raw.channel_change, 1);
        mSoundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            // Consider the pool ready once the last-loaded sample (channel_change) is in
            if (sampleId == mSoundChannelChange) mSoundPoolReady = true;
        });

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        // Instantiate overlay view
        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        // GPS + battery reporter (started in onConnectionSynchronized, stopped on disconnect)
        mLocationReporter = new LocationReporter(this, mSettings);

        // Prewarm the shift controller so its TTS engine is ready by the
        // time the user triple-taps. Without this, the first toggle is
        // silent (TTS takes ~1-2s to initialize on a cold start).
        prewarmShiftController();

        // Feature-flag cache. Kick off an immediate background fetch so
        // gated features respect the current admin state within seconds
        // of startup, then re-poll every 10 min for live toggle updates.
        mCapabilities = new CapabilitiesClient(this);
        refreshCapabilitiesInBackground();
        mCapabilitiesHandler = new Handler(Looper.getMainLooper());
        mCapabilitiesHandler.postDelayed(mCapabilitiesTick, CAPABILITIES_FIRST_DELAY_MS);

        // Set up TTS
        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        mTalkReceiver = new TalkBroadcastReceiver(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
        unregisterObserver(mObserver);
        if(mTTS != null) { mTTS.shutdown(); mTTSReady = false; }
        if(mSoundPool != null) mSoundPool.release();
        if(mLocationReporter != null) mLocationReporter.stop();
        if(mShiftController != null) { mShiftController.shutdown(); mShiftController = null; }
        if(mCapabilitiesHandler != null) {
            mCapabilitiesHandler.removeCallbacks(mCapabilitiesTick);
            mCapabilitiesHandler = null;
        }
        mMessageLog = null;
        mMessageNotification.dismiss();
        super.onDestroy();
    }

    @Override
    public void onConnectionSynchronized() {
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }

        // Restore mute/deafen state
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK));
        }

        // Auto-join default channel if configured
        String defaultChannel = mSettings.getDefaultChannel();
        if (defaultChannel != null && !defaultChannel.isEmpty()) {
            try {
                IChannel root = getRootChannel();
                if (root != null) {
                    List<IChannel> channels = se.lublin.mumla.util.ModelUtils.getChannelList(root);
                    for (IChannel ch : channels) {
                        if (defaultChannel.equals(ch.getName())) {
                            joinChannel(ch.getId());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "auto-join default channel failed: " + e);
            }
        }

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }

        // Start GPS + battery reporting to Traccar
        if (mLocationReporter != null) {
            try {
                String username = getTargetServer() != null ? getTargetServer().getUsername() : null;
                mLocationReporter.start(username);
            } catch (Exception e) {
                Log.d(TAG, "failed to start LocationReporter: " + e);
            }
        }
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException iae) {
        }

        // Stop GPS reporting
        if (mLocationReporter != null) {
            mLocationReporter.stop();
        }

        // Remove overlay if present.
        mChannelOverlay.hide();

        mHotCorner.setShown(false);

        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                /* Convert input method defined in settings to an integer format used by Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                                     AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled()) {
                    mTTSReady = false;
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                } else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                    mTTSReady = false;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                // Reconfigure the service appropriately.
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if(on) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null) mProximityLock.release();
            mProximityLock = null;
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onOverlayToggled() {
        // Ditch notification shade/panel to make overlay presence/permission request visible.
        // But on Android 12 that's no longer allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Intent close = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(close);
        }

        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        connect();
    }

    @Override
    public void cancelReconnect() {
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        super.cancelReconnect();
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    // Triple-tap PTT detection for lone-worker shift toggle.
    // The P50 ROM owns the F3 long-press (can't be intercepted), so we
    // layer a tap-timing gesture on top of the PTT button: three presses
    // within TRIPLE_TAP_WINDOW_MS flips shift state.
    private static final long TRIPLE_TAP_WINDOW_MS = 1200;
    private final long[] mPttPressTimes = new long[3];
    private int mPttPressIdx = 0;

    @Override
    public void onTalkKeyDown() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
                setTalkingState(true); // Start talking
            }
        }
        // Record press time and check for triple-tap regardless of input
        // method — the user has signalled a shift-toggle intent.
        detectTripleTap();
    }

    private void detectTripleTap() {
        // Gate 0: Admin feature flag. Overrides every per-device setting —
        // if the operator has turned lone-worker off fleet-wide, the
        // gesture must not fire even on devices where the user has
        // enabled the local toggle.
        if (!hasFeature("lone_worker")) {
            return;
        }
        // Gate 1: Lone-worker mode must be explicitly enabled. Users who
        // never run shifts (e.g. dispatchers, phone operators) should
        // never accidentally start one with fast PTT taps.
        if (!mSettings.isLoneWorkerEnabled()) {
            return;
        }
        // Gate 2: Never in the Phone channel. Triple-tap during a live
        // phone call is reserved for call-control gestures in the
        // sip-bridge, and starting a shift in the middle of a call is
        // never what the user means.
        if ("Phone".equals(currentMumbleChannelName())) {
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        mPttPressTimes[mPttPressIdx] = now;
        mPttPressIdx = (mPttPressIdx + 1) % mPttPressTimes.length;

        // All three slots must be populated AND the oldest must be within
        // the window. If so, fire a shift toggle and reset the ring so
        // the fourth tap doesn't cascade.
        long oldest = now;
        for (long t : mPttPressTimes) {
            if (t == 0) return; // still priming
            if (t < oldest) oldest = t;
        }
        if (now - oldest <= TRIPLE_TAP_WINDOW_MS) {
            Log.i(TAG, "triple-tap PTT detected; toggling shift");
            java.util.Arrays.fill(mPttPressTimes, 0);
            mPttPressIdx = 0;
            shiftController().toggle(currentMumbleUsername());
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyUp() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking()); // Toggle talk state
            } else if (isTalking()) {
                setTalkingState(false); // Stop talking
            }
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    @Override
    public void switchChannel(int direction) {
        if (!isConnectionEstablished()) return;
        try {
            IChannel root = getRootChannel();
            if (root == null) return;
            // Cycle direct children of Root in the SAME filtered+
            // alphabetical-sorted order the carousel displays, otherwise
            // turning the knob clockwise can visually jump the carousel
            // to a channel that's to the left of current (because the
            // server-order next channel is alphabetically earlier).
            List<? extends IChannel> raw = root.getSubchannels();
            if (raw == null || raw.isEmpty()) return;
            java.util.List<IChannel> channels = new java.util.ArrayList<>();
            for (IChannel c : raw) {
                String nm = c.getName() == null ? "" : c.getName();
                if (!nm.startsWith("Call-")) channels.add(c);
            }
            java.util.Collections.sort(channels, new java.util.Comparator<IChannel>() {
                @Override public int compare(IChannel a, IChannel b) {
                    String an = a.getName() == null ? "" : a.getName();
                    String bn = b.getName() == null ? "" : b.getName();
                    return an.compareToIgnoreCase(bn);
                }
            });
            if (channels.isEmpty()) return;

            IChannel current = getSessionChannel();
            int currentIndex = -1;
            for (int i = 0; i < channels.size(); i++) {
                if (current != null && channels.get(i).getId() == current.getId()) {
                    currentIndex = i;
                    break;
                }
            }

            int nextIndex;
            if (currentIndex == -1) {
                // Current channel is a subchannel or Root — jump to first main channel
                nextIndex = 0;
            } else {
                nextIndex = (currentIndex + direction + channels.size()) % channels.size();
            }

            IChannel target = channels.get(nextIndex);
            joinChannel(target.getId());
            Toast.makeText(this, getString(R.string.channel_switched, target.getName()),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "switchChannel failed: " + e);
        }
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }
}
