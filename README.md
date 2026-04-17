# openPTT TRX

A dispatch-radio Mumble client for **Hytera P50** handhelds, forked from
[Mumla](https://gitlab.com/quite/mumla). Bundles voice PTT, hardware-button
handling, GPS + battery reporting to Traccar, and always-on "radio" behaviour
into a single `.apk` so each P50 is a one-app dispatch device.

Package name: `ch.harro.openptt`
Upstream: Mumla (GPL-3.0) — this fork tracks Mumla; pull from `upstream` to
sync.

---

## Why a fork?

The stock Mumla client works fine for voice but the Hytera P50 is a very
specific device: **240×320 screen, no touch keyboard, rotary channel knob,
dedicated PTT button behind a proprietary broadcast, GPS hardware, Meig ROM
that kills background apps aggressively.** This fork bakes in all the things
you'd otherwise need external config, extra apps, or manual taps to get:
single app, boot-to-connected, hands-free channel feedback.

---

## What's in the fork

### Hardware integration (Hytera P50)

- **Hardware PTT button** — the P50's dedicated PTT key is intercepted by the
  Meig ROM and published as a system broadcast
  (`com.meigsmart.meigkeyaccessibility.onkeyevent`) rather than a normal
  Android key event. A manifest-declared `MeigPttReceiver` catches it and
  forwards PTT-down/up to `MumlaService` via explicit intents. Works even
  when the activity isn't in the foreground.
- **Rotary channel knob** — the physical knob emits `KEYCODE_F5` /
  `KEYCODE_F6`. `MumlaActivity.dispatchKeyEvent()` cycles through the Root
  channel's direct children in position order.
- **DPAD free for navigation** — `talkKey` is `-1` by default so up/down/left/
  right drive list focus. PTT goes through the Meig broadcast instead.
- **GPS + battery reporting to Traccar** (no separate Traccar Client app) —
  `LocationReporter` registers with Android's `LocationManager`, throttles
  to 30 s / 50 m, reads battery via `BATTERY_CHANGED` sticky intent, and
  POSTs the OsmAnd-format URL to the configured Traccar endpoint on port
  5055 using the Mumble username as the Traccar device `uniqueId`. Starts
  on `onConnectionSynchronized`, stops on disconnect.

### Radio-style UX

- **TTS channel-name announcement** — on channel change the app speaks the
  new channel name (e.g. *"Weather"*) via the system TextToSpeech engine.
  `QUEUE_FLUSH` so rapid knob turns only announce the final channel.
  Falls back to a short beep if TTS isn't enabled or the engine hasn't
  finished initialising.
- **Auto-connect on app start** — `PREF_AUTO_CONNECT` (default on) connects
  to the first favourite as soon as the activity is created.
- **Auto-start on device boot** — `BootReceiver` handles `BOOT_COMPLETED` +
  `QUICKBOOT_POWERON`, launches `MumlaActivity` which then auto-connects.
- **Default channel auto-join** — `PREF_DEFAULT_CHANNEL` (default "Internal")
  joins the named channel after connect, so the device lands exactly where
  the operator expects.
- **Root channel hidden** — `ChannelListAdapter.constructNodes()` skips the
  Root node and renders its children at depth 0. More screen space, cleaner
  list.
- **Connect sound** plays a short tone on server connect. Removed the
  transmit-side "roger beep" — it couldn't actually be transmitted through
  the encoder, so local-only was useless.

### Small-screen (240×320) tailoring

- `res/values-small/dimens.xml` — tighter paddings for 120 dpi.
- `res/layout-small/activity_main.xml` — 36 dp toolbar, 200 dp drawer.
- Default PTT button height 60 dp (was 150 dp).
- Tab strip between channel list and chat hidden on small screens.
- Brighter dark theme palette (`values-night/themes.xml`, `colors.xml`) for
  the low-contrast P50 screen.
- Drawer navigation shortcuts — tap the app logo *or* the connected-server
  header to jump back to the channel list without hunting for "All Channels".

### Background keep-alive

- Foreground service type declares `microphone|location`.
- `network_security_config.xml` whitelists cleartext HTTP only for the
  Traccar OsmAnd endpoint (`voice.harro.ch` / port 5055).
- Donate footer removed from the drawer.
- Per-device provisioning via ADB pins the app to:
  - Doze whitelist (`dumpsys deviceidle whitelist +ch.harro.openptt`)
  - Standby bucket EXEMPTED (5)
  - `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND` allow
  - `stay_awake=true` + `autoReconnect=true` shared prefs

---

## Build

Requires JDK 21 and Android SDK (the `fossDebug` variant uses no Google
Services).

```bash
JAVA_HOME=/path/to/jdk-21 \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew assembleFossDebug
```

Output: `app/build/outputs/apk/foss/debug/openptt-foss-debug.apk`.

---

## Provisioning a P50

High-level steps (full walkthrough in the PTT server's
[`docs/p50-setup.md`](https://github.com/harrowiersma/PTT/blob/main/docs/p50-setup.md)):

1. Enable USB debugging on the P50.
2. Remove any old PTT apps:
   ```bash
   adb uninstall org.traccar.client
   adb uninstall com.hammumble
   ```
3. Install the APK:
   ```bash
   adb install -r openptt-foss-debug.apk
   ```
4. Grant runtime permissions:
   ```bash
   adb shell pm grant ch.harro.openptt android.permission.RECORD_AUDIO
   adb shell pm grant ch.harro.openptt android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant ch.harro.openptt android.permission.ACCESS_COARSE_LOCATION
   ```
5. Seed the favourite server (`voice.harro.ch:443`) directly into
   `/data/data/ch.harro.openptt/databases/mumble.db` via `run-as` + `sqlite3`,
   or let the user configure through the UI.
6. Seed `shared_prefs` with `audioInputMethod=ptt`, `talkKey=-1`,
   `traccar_url=http://voice.harro.ch:5055`, `gps_tracking=true`,
   `auto_connect=true`.
7. Apply background keep-alive — a ready-made script is at
   `/tmp/openptt_keepalive.sh` on the dev machine (or duplicate the commands
   in "Background keep-alive" above).

---

## Settings overview

| Screen | Key | Default | Effect |
|---|---|---|---|
| General | `auto_connect` | `true` | Connect to first favourite on app launch |
| General | `default_channel` | `Internal` | Auto-join this channel after connect |
| General → GPS | `gps_tracking` | `true` | Stream GPS + battery while connected |
| General → GPS | `traccar_url` | *(empty)* | OsmAnd endpoint, e.g. `http://voice.harro.ch:5055` |
| Audio | `audioInputMethod` | `ptt` | PTT / voice-activity / continuous |
| Audio | `talkKey` | `-1` | `-1` lets DPAD handle navigation; Meig broadcast handles the hardware PTT |
| Audio | `notification_sounds` | `true` | Connect sound + channel-change beep (fallback for TTS) |
| General | `useTts` | `true` | Speak channel name on join and message TTS |

---

## Server side

This client assumes a matching
[PTT admin server](https://github.com/harrowiersma/PTT):

- Murmur for voice on `voice.harro.ch:443` (SNI-routed by Nginx).
- Traccar on port 5055 (OsmAnd) and internal 8082 (REST).
- PTT admin FastAPI on `ptt.harro.ch` — owns user + Traccar device lifecycle.

When an admin creates a user, the server auto-creates a Traccar device with
`uniqueId = <mumble_username>`, so the app (which sends `?id=<username>` in
its OsmAnd POSTs) is recognised with zero per-device configuration.

---

## Syncing from upstream Mumla

```bash
git fetch upstream
git merge upstream/master
# resolve any conflicts in the fork-delta files, then
./gradlew assembleFossDebug
```

---

## License

GPL-3.0 (inherited from Mumla).
