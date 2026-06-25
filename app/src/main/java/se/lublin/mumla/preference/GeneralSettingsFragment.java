package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import java.util.Locale;
import java.util.Set;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class GeneralSettingsFragment extends MumlaPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String USE_TOR_KEY = "useTor";
    private static final String BT_PTT_ENABLED_KEY = "bt_ptt_enabled";
    private static final String BT_PTT_PROBE_KEY = "bt_ptt_probe";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        Preference probePref = getPreferenceScreen().findPreference(BT_PTT_PROBE_KEY);
        if (probePref != null) {
            probePref.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), BtPttProbeActivity.class));
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBtPttSummary();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (BT_PTT_ENABLED_KEY.equals(key)) refreshBtPttSummary();
    }

    private void refreshBtPttSummary() {
        Preference pref = getPreferenceScreen().findPreference(BT_PTT_ENABLED_KEY);
        if (pref == null) return;
        Settings s = Settings.getInstance(requireContext());
        if (!s.isBtPttEnabled()) {
            pref.setSummary(R.string.btPttEnabledSum);
            return;
        }
        String pairStatus = findPairedRing();
        if (pairStatus == null) {
            pref.setSummary(R.string.btPttEnabledSumUnpaired);
        } else {
            pref.setSummary(getString(R.string.btPttEnabledSumPaired, pairStatus));
        }
    }

    /** Returns "POA121 (9C:06:6E:F7:F1:63)"-style label if a Hytera ring
     *  is bonded, null if not (or if we can't read bonded devices). */
    @SuppressLint("MissingPermission")
    private String findPairedRing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        BluetoothManager bm = (BluetoothManager)
                requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return null;
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) return null;
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException e) { return null; }
        if (bonded == null) return null;
        for (BluetoothDevice d : bonded) {
            String name;
            try { name = d.getName(); } catch (SecurityException e) { continue; }
            if (name == null) continue;
            String up = name.toUpperCase(Locale.ROOT);
            if (up.contains("POA") || up.contains("HYTERA")) {
                return name + " (" + d.getAddress() + ")";
            }
        }
        return null;
    }
}
