package com.brouken.player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

public class SettingsActivity extends AppCompatActivity {

    static RecyclerView recyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
            getWindow().setNavigationBarColor(Color.TRANSPARENT);

            if (Build.VERSION.SDK_INT >= 35) {
                int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    getWindow().getDecorView().setSystemUiVisibility(0);
                } else if (nightModeFlags == Configuration.UI_MODE_NIGHT_NO) {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            }
        }

        super.onCreate(savedInstanceState);

        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            LinearLayout layout = findViewById(R.id.settings_layout);
            layout.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                view.setPadding(windowInsets.getSystemWindowInsetLeft(),
                        windowInsets.getSystemWindowInsetTop(),
                        windowInsets.getSystemWindowInsetRight(),
                        0);
                if (recyclerView != null) {
                    recyclerView.setPadding(0,0,0, windowInsets.getSystemWindowInsetBottom());
                }
                windowInsets.consumeSystemWindowInsets();
                return windowInsets;
            });
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            Preference preferenceAutoPiP = findPreference("autoPiP");
            if (preferenceAutoPiP != null) {
                preferenceAutoPiP.setEnabled(Utils.isPiPSupported(this.getContext()));
            }
            Preference preferenceFrameRateMatching = findPreference("frameRateMatching");
            if (preferenceFrameRateMatching != null) {
                preferenceFrameRateMatching.setEnabled(Build.VERSION.SDK_INT >= 23);
            }
            ListPreference listPreferenceFileAccess = findPreference("fileAccess");
            if (listPreferenceFileAccess != null) {
                List<String> entries = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.file_access_entries)));
                List<String> values = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.file_access_values)));
                if (Build.VERSION.SDK_INT < 30) {
                    int index = values.indexOf("mediastore");
                    entries.remove(index);
                    values.remove(index);
                }
                if (!Utils.hasSAFChooser(getContext().getPackageManager())) {
                    int index = values.indexOf("saf");
                    entries.remove(index);
                    values.remove(index);
                }
                listPreferenceFileAccess.setEntries(entries.toArray(new String[0]));
                listPreferenceFileAccess.setEntryValues(values.toArray(new String[0]));
            }

            setupLanguagePreference(
                    PlusPrefs.KEY_AUDIO_LANGUAGE_PRIMARY, true, true, false);
            setupLanguagePreference(
                    PlusPrefs.KEY_AUDIO_LANGUAGE_SECONDARY, true, true, true);
            setupLanguagePreference(
                    PlusPrefs.KEY_AUDIO_LANGUAGE_TERTIARY, true, true, true);
            setupLanguagePreference(
                    PlusPrefs.KEY_SUBTITLE_LANGUAGE_PRIMARY, true, true, false);
            setupLanguagePreference(
                    PlusPrefs.KEY_SUBTITLE_LANGUAGE_SECONDARY, true, true, true);
            setupLanguagePreference(
                    PlusPrefs.KEY_SUBTITLE_LANGUAGE_TERTIARY, true, true, true);

            EditTextPreference subtitleDelay = findPreference(PlusPrefs.KEY_SUBTITLE_DELAY_MS);
            if (subtitleDelay != null) {
                subtitleDelay.setOnBindEditTextListener(editText -> editText.setInputType(
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
                subtitleDelay.setSummaryProvider(preference -> {
                    EditTextPreference editTextPreference = (EditTextPreference) preference;
                    String value = editTextPreference.getText();
                    return (value == null || value.isEmpty() ? "0" : value) + " ms";
                });
            }

            Preference diagnostics = findPreference("externalPlayerDiagnosticsView");
            if (diagnostics != null) {
                diagnostics.setOnPreferenceClickListener(preference -> {
                    showDiagnostics();
                    return true;
                });
            }

            Preference connector = findPreference(PlusPrefs.KEY_STREMIO_CONNECTOR_ENABLED);
            if (connector != null) {
                connector.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = Boolean.TRUE.equals(newValue);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (enabled) {
                            requestConnectorNotificationPermission();
                            StremioConnectorService.start(requireContext());
                        } else {
                            StremioConnectorService.stop(requireContext());
                            new StremioConnectorStore(requireContext()).clear();
                        }
                    });
                    return true;
                });
            }

            Preference installConnector = findPreference("stremioConnectorInstall");
            if (installConnector != null) {
                installConnector.setOnPreferenceClickListener(preference -> {
                    Context context = requireContext();
                    if (!StremioConnectorService.start(context)) {
                        Toast.makeText(context,
                                R.string.pref_stremio_connector_install_failed,
                                Toast.LENGTH_LONG).show();
                        return true;
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(StremioConnectorService.INSTALL_URL));
                            startActivity(intent);
                        } catch (RuntimeException error) {
                            Toast.makeText(context,
                                    R.string.pref_stremio_connector_install_failed,
                                    Toast.LENGTH_LONG).show();
                        }
                    }, 350L);
                    return true;
                });
            }

            if (new PlusPrefs(requireContext()).stremioConnectorEnabled) {
                StremioConnectorService.start(requireContext());
            }
        }

        private void requestConnectorNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33
                    && requireContext().checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 16745);
            }
        }

        private void showDiagnostics() {
            Context context = requireContext();
            String log = ExternalPlayerDiagnostics.read(context);
            String visibleLog = log.isEmpty()
                    ? getString(R.string.pref_external_player_diagnostics_empty) : log;
            new AlertDialog.Builder(context)
                    .setTitle(R.string.pref_external_player_diagnostics_view)
                    .setMessage(visibleLog)
                    .setPositiveButton(R.string.pref_external_player_diagnostics_copy,
                            (dialog, which) -> {
                                ClipboardManager clipboard = (ClipboardManager)
                                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText(
                                            "JustPlayer Plus diagnostics", visibleLog));
                                    Toast.makeText(context,
                                            R.string.pref_external_player_diagnostics_copied,
                                            Toast.LENGTH_SHORT).show();
                                }
                            })
                    .setNeutralButton(R.string.pref_external_player_diagnostics_clear,
                            (dialog, which) -> ExternalPlayerDiagnostics.clear(context))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void setupLanguagePreference(String key, boolean includeDefault,
                                             boolean includeDevice, boolean includeNone) {
            ListPreference preference = findPreference(key);
            if (preference == null) {
                return;
            }

            LinkedHashMap<String, String> entries = new LinkedHashMap<>();
            if (includeNone) {
                entries.put(PlusPrefs.TRACK_NONE, getString(R.string.pref_language_track_none));
            }
            if (includeDefault) {
                entries.put(Prefs.TRACK_DEFAULT, getString(R.string.pref_language_track_default));
            }
            if (includeDevice) {
                entries.put(Prefs.TRACK_DEVICE, getString(R.string.pref_language_track_device));
            }
            entries.putAll(getLanguages());

            preference.setEntries(entries.values().toArray(new String[0]));
            preference.setEntryValues(entries.keySet().toArray(new String[0]));
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            if (Build.VERSION.SDK_INT >= 29) {
                recyclerView = getListView();
            }
        }

        LinkedHashMap<String, String> getLanguages() {
            LinkedHashMap<String, String> languages = new LinkedHashMap<>();
            for (Locale locale : Locale.getAvailableLocales()) {
                try {
                    // MissingResourceException: Couldn't find 3-letter language code for zz
                    String key = locale.getISO3Language();
                    String language = locale.getDisplayLanguage();
                    if (key.isEmpty() || language.isEmpty()) {
                        continue;
                    }
                    int length = language.offsetByCodePoints(0, 1);
                    language = language.substring(0, length).toUpperCase(locale)
                            + language.substring(length);
                    String value = language + " [" + key + "]";
                    languages.put(key, value);
                } catch (MissingResourceException e) {
                    e.printStackTrace();
                }
            }
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            Utils.orderByValue(languages, collator::compare);
            return languages;
        }
    }
}
