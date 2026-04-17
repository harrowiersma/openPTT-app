/*
 * Reports GPS position + battery level to a Traccar server via OsmAnd protocol.
 * Lifecycle is tied to MumlaService connection state: start on connect, stop on disconnect.
 */

package se.lublin.mumla.service;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import se.lublin.mumla.Settings;

public class LocationReporter {
    private static final String TAG = "LocationReporter";

    /** Minimum time between fixes (ms) */
    private static final long MIN_TIME_MS = 30_000L;
    /** Minimum distance between fixes (meters) */
    private static final float MIN_DISTANCE_M = 50f;
    /** HTTP connection timeouts (ms) */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final Context mContext;
    private final Settings mSettings;
    private final LocationManager mLocationManager;
    private final Handler mMainHandler;

    private String mUsername;
    private boolean mRunning;

    private final LocationListener mListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            sendPositionAsync(location);
        }

        // Empty overrides required on older API levels
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override
        public void onProviderEnabled(String provider) { }
        @Override
        public void onProviderDisabled(String provider) { }
    };

    public LocationReporter(Context context, Settings settings) {
        mContext = context.getApplicationContext();
        mSettings = settings;
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start receiving location updates and reporting them to Traccar.
     * Safe to call multiple times; subsequent calls are no-ops until stop().
     */
    public void start(String username) {
        if (mRunning) return;
        if (username == null || username.isEmpty()) {
            Log.w(TAG, "start() called with empty username — skipping");
            return;
        }
        if (!mSettings.isGpsTrackingEnabled()) {
            Log.i(TAG, "GPS tracking disabled in settings");
            return;
        }
        String url = mSettings.getTraccarUrl();
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "Traccar URL not configured — GPS reporting disabled");
            return;
        }
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted — GPS reporting disabled");
            return;
        }
        if (mLocationManager == null) {
            Log.e(TAG, "LocationManager unavailable");
            return;
        }

        mUsername = username;
        mRunning = true;

        // requestLocationUpdates must be called on a thread with a Looper; use main.
        mMainHandler.post(() -> {
            try {
                if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    mLocationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, mListener);
                    Log.i(TAG, "Registered GPS provider updates for user=" + username);
                }
                if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    mLocationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, mListener);
                    Log.i(TAG, "Registered NETWORK provider updates for user=" + username);
                }
                // Send an initial fix from last-known location if available
                Location last = null;
                try {
                    last = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                } catch (SecurityException ignored) { }
                if (last == null) {
                    try {
                        last = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    } catch (SecurityException ignored) { }
                }
                if (last != null) {
                    sendPositionAsync(last);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException requesting location updates: " + e);
                mRunning = false;
            }
        });
    }

    /** Stop receiving location updates. Safe to call multiple times. */
    public void stop() {
        if (!mRunning) return;
        mRunning = false;
        mMainHandler.post(() -> {
            try {
                mLocationManager.removeUpdates(mListener);
                Log.i(TAG, "Unregistered location updates");
            } catch (Exception e) {
                Log.w(TAG, "Error removing location updates: " + e);
            }
        });
    }

    private void sendPositionAsync(Location location) {
        final String url = mSettings.getTraccarUrl();
        final String username = mUsername;
        if (url == null || url.isEmpty() || username == null) return;

        final int batteryPct = readBatteryPercent();
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        final long ts = location.getTime() / 1000L; // Traccar expects seconds
        final float speed = location.hasSpeed() ? location.getSpeed() : 0f;
        final float bearing = location.hasBearing() ? location.getBearing() : 0f;
        final float accuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;
        final double altitude = location.hasAltitude() ? location.getAltitude() : 0d;

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                StringBuilder qs = new StringBuilder();
                qs.append("id=").append(URLEncoder.encode(username, StandardCharsets.UTF_8.name()));
                qs.append("&timestamp=").append(ts);
                qs.append("&lat=").append(lat);
                qs.append("&lon=").append(lon);
                qs.append("&speed=").append(speed);
                qs.append("&bearing=").append(bearing);
                qs.append("&accuracy=").append(accuracy);
                qs.append("&altitude=").append(altitude);
                if (batteryPct >= 0) {
                    qs.append("&batt=").append(batteryPct);
                }

                String base = url;
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                URL reqUrl = new URL(base + "/?" + qs);

                conn = (HttpURLConnection) reqUrl.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setDoOutput(false);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    Log.d(TAG, "Traccar report OK (" + code + ") user=" + username
                            + " lat=" + lat + " lon=" + lon + " batt=" + batteryPct);
                } else {
                    Log.w(TAG, "Traccar report HTTP " + code + " for " + reqUrl);
                }
            } catch (IOException e) {
                Log.w(TAG, "Traccar report failed: " + e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "LocationReporter-send").start();
    }

    /** Returns battery percentage 0-100, or -1 if unavailable. */
    private int readBatteryPercent() {
        try {
            Intent intent = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return -1;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return (int) Math.round(level * 100.0 / scale);
        } catch (Exception e) {
            return -1;
        }
    }
}
