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
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.brouken.player.aisubtitles.AiSubtitlePreferences;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

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
        private static final String KEY_OPENSUBTITLES_CREDENTIALS =
                "openSubtitlesCredentials";
        private static final String KEY_OPENSUBTITLES_TEST = "openSubtitlesTest";

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

            setupAiSubtitlePreferences();
            setupOpenSubtitlesPreferences();

            Preference diagnostics = findPreference("externalPlayerDiagnosticsView");
            if (diagnostics != null) {
                diagnostics.setOnPreferenceClickListener(preference -> {
                    showDiagnostics();
                    return true;
                });
            }

            SwitchPreferenceCompat connector =
                    findPreference(PlusPrefs.KEY_STREMIO_CONNECTOR_ENABLED);
            if (connector != null) {
                connector.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = Boolean.TRUE.equals(newValue);
                    Context context = requireContext().getApplicationContext();
                    boolean persisted = PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putBoolean(PlusPrefs.KEY_STREMIO_CONNECTOR_ENABLED, enabled)
                            .commit();
                    if (!persisted) {
                        return false;
                    }
                    connector.setChecked(enabled);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (enabled) {
                            requestConnectorNotificationPermission();
                            StremioConnectorService.start(context);
                        } else {
                            StremioConnectorService.stop(context);
                            new StremioConnectorStore(context).clear();
                        }
                    });
                    return false;
                });
            }

            Preference installConnector = findPreference("stremioConnectorInstall");
            if (installConnector != null) {
                installConnector.setOnPreferenceClickListener(preference -> {
                    Context context = requireContext();
                    if (!StremioConnectorService.start(context)) {
                        Toast.makeText(context,
                                R.string.pref_stremio_connector_start_failed,
                                Toast.LENGTH_LONG).show();
                        return true;
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        ClipboardManager clipboard = (ClipboardManager)
                                context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) {
                            Toast.makeText(context,
                                    R.string.pref_stremio_connector_copy_failed,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "JustPlayer Plus connector",
                                StremioConnectorService.HTTP_MANIFEST_URL));
                        Toast.makeText(context,
                                R.string.pref_stremio_connector_install_instructions,
                                Toast.LENGTH_LONG).show();
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(StremioConnectorService.STREMIO_ADDONS_URL));
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

        private void setupAiSubtitlePreferences() {
            SwitchPreferenceCompat enabled = findPreference(
                    PlusPrefs.KEY_AI_SUBTITLES_ENABLED);
            EditTextPreference backend = findPreference(
                    PlusPrefs.KEY_AI_SUBTITLE_BACKEND_URL);
            EditTextPreference apiToken = findPreference(AiSubtitlePreferences.KEY_API_TOKEN);
            Preference target = findPreference(
                    PlusPrefs.KEY_AI_SUBTITLE_TARGET_LANGUAGE);
            Preference httpWarning = findPreference("aiSubtitleHttpWarning");
            if (enabled == null || backend == null || apiToken == null
                    || target == null || httpWarning == null) {
                return;
            }

            backend.setOnBindEditTextListener(editText -> editText.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
            backend.setSummaryProvider(preference -> {
                String value = ((EditTextPreference) preference).getText();
                return value == null || value.trim().isEmpty()
                        ? getString(R.string.pref_ai_subtitle_backend_not_set)
                        : value.trim();
            });
            apiToken.setOnBindEditTextListener(editText -> {
                editText.setSingleLine(true);
                editText.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            });

            Runnable refreshVisibility = () -> {
                boolean isEnabled = enabled.isChecked();
                String url = backend.getText();
                backend.setVisible(isEnabled);
                apiToken.setVisible(isEnabled);
                target.setVisible(isEnabled);
                httpWarning.setVisible(isEnabled && url != null
                        && url.trim().toLowerCase(Locale.ROOT).startsWith("http://"));
            };
            enabled.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean isEnabled = Boolean.TRUE.equals(newValue);
                backend.setVisible(isEnabled);
                apiToken.setVisible(isEnabled);
                target.setVisible(isEnabled);
                String url = backend.getText();
                httpWarning.setVisible(isEnabled && url != null
                        && url.trim().toLowerCase(Locale.ROOT).startsWith("http://"));
                return true;
            });
            backend.setOnPreferenceChangeListener((preference, newValue) -> {
                String url = newValue == null ? "" : newValue.toString().trim();
                httpWarning.setVisible(enabled.isChecked()
                        && url.toLowerCase(Locale.ROOT).startsWith("http://"));
                return true;
            });
            refreshVisibility.run();
        }

        private void setupOpenSubtitlesPreferences() {
            Preference credentialsPreference = findPreference(
                    KEY_OPENSUBTITLES_CREDENTIALS);
            Preference testPreference = findPreference(
                    KEY_OPENSUBTITLES_TEST);
            if (credentialsPreference == null || testPreference == null) {
                return;
            }
            OpenSubtitlesCredentialsStore store =
                    new OpenSubtitlesCredentialsStore(requireContext());
            Runnable refresh = () -> {
                OpenSubtitlesCredentialsStore.Credentials credentials = store.load();
                if (credentials == null) {
                    credentialsPreference.setSummary(
                            R.string.pref_opensubtitles_credentials_missing);
                    testPreference.setEnabled(false);
                } else if (credentials.hasAccount()) {
                    credentialsPreference.setSummary(
                            R.string.pref_opensubtitles_credentials_account);
                    testPreference.setEnabled(true);
                } else {
                    credentialsPreference.setSummary(
                            R.string.pref_opensubtitles_credentials_key_only);
                    testPreference.setEnabled(true);
                }
            };
            credentialsPreference.setOnPreferenceClickListener(preference -> {
                showOpenSubtitlesCredentialsDialog(store, refresh);
                return true;
            });
            testPreference.setOnPreferenceClickListener(preference -> {
                testOpenSubtitlesCredentials(store);
                return true;
            });
            refresh.run();
        }

        private void showOpenSubtitlesCredentialsDialog(
                OpenSubtitlesCredentialsStore store, Runnable refresh) {
            Context context = requireContext();
            OpenSubtitlesCredentialsStore.Credentials existing = store.load();
            int padding = Math.round(20f * getResources().getDisplayMetrics().density);

            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(padding, padding / 2, padding, 0);

            TextView explanation = new TextView(context);
            explanation.setText(R.string.pref_opensubtitles_credentials_hint);
            explanation.setPadding(0, 0, 0, padding / 2);
            layout.addView(explanation);

            EditText apiKey = credentialField(
                    context, R.string.pref_opensubtitles_api_key, true);
            EditText username = credentialField(
                    context, R.string.pref_opensubtitles_username, false);
            EditText password = credentialField(
                    context, R.string.pref_opensubtitles_password, true);
            if (existing != null) {
                apiKey.setHint(getString(R.string.pref_opensubtitles_api_key) + " ••••••••");
                if (!existing.username.isEmpty()) {
                    username.setText(existing.username);
                }
                if (existing.hasAccount()) {
                    password.setHint(
                            getString(R.string.pref_opensubtitles_password) + " ••••••••");
                }
            }
            layout.addView(apiKey);
            layout.addView(username);
            layout.addView(password);

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.pref_opensubtitles_credentials_dialog)
                    .setView(layout)
                    .setPositiveButton(R.string.pref_opensubtitles_save, null)
                    .setNeutralButton(R.string.pref_opensubtitles_clear, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String enteredKey = apiKey.getText().toString().trim();
                    String enteredUsername = username.getText().toString().trim();
                    String enteredPassword = password.getText().toString();
                    String resolvedKey = enteredKey.isEmpty() && existing != null
                            ? existing.apiKey : enteredKey;
                    String resolvedUsername;
                    String resolvedPassword;
                    if (enteredUsername.isEmpty() && enteredPassword.isEmpty()
                            && existing != null && existing.hasAccount()) {
                        resolvedUsername = existing.username;
                        resolvedPassword = existing.password;
                    } else {
                        resolvedUsername = enteredUsername;
                        resolvedPassword = enteredPassword;
                    }
                    OpenSubtitlesCredentialsStore.Credentials credentials =
                            new OpenSubtitlesCredentialsStore.Credentials(
                                    resolvedKey, resolvedUsername, resolvedPassword);
                    if (!credentials.isValid()) {
                        Toast.makeText(context,
                                R.string.pref_opensubtitles_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!store.save(credentials)) {
                        Toast.makeText(context,
                                R.string.pref_opensubtitles_secure_store_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    refresh.run();
                    Toast.makeText(context,
                            R.string.pref_opensubtitles_saved,
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    store.clear();
                    refresh.run();
                    Toast.makeText(context,
                            R.string.pref_opensubtitles_cleared,
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            });
            dialog.show();
        }

        private EditText credentialField(Context context, int hint, boolean secret) {
            EditText field = new EditText(context);
            field.setHint(hint);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT
                    | (secret
                    ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
            field.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return field;
        }

        private void testOpenSubtitlesCredentials(OpenSubtitlesCredentialsStore store) {
            Context context = requireContext();
            OpenSubtitlesCredentialsStore.Credentials credentials = store.load();
            if (credentials == null) {
                Toast.makeText(context,
                        R.string.pref_opensubtitles_credentials_missing,
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(context,
                    R.string.pref_opensubtitles_test_running,
                    Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build();
                OpenSubtitlesRestClient.TestResult result =
                        OpenSubtitlesRestClient.testCredentials(client, credentials);
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (result.success) {
                        Toast.makeText(requireContext(),
                                result.accountVerified
                                        ? R.string.pref_opensubtitles_test_ok_account
                                        : R.string.pref_opensubtitles_test_ok_key,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(),
                                getString(R.string.pref_opensubtitles_test_failed,
                                        result.reason),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }, "opensubtitles-settings-test").start();
        }

        private void showDiagnostics() {
            Context context = requireContext();
            String log = ExternalPlayerDiagnostics.read(context);
            String visibleLog = log.isEmpty()
                    ? getString(R.string.pref_external_player_diagnostics_empty) : log;

            int padding = Math.round(16f * getResources().getDisplayMetrics().density);
            TextView logView = new TextView(context);
            logView.setText(visibleLog);
            logView.setTextSize(12f);
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setPadding(padding, padding, padding, padding);
            logView.setFocusable(false);

            ScrollView scrollView = new ScrollView(context);
            scrollView.setFillViewport(true);
            scrollView.setFocusable(true);
            scrollView.setFocusableInTouchMode(true);
            scrollView.setVerticalScrollBarEnabled(true);
            scrollView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            scrollView.addView(logView, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            AlertDialog alertDialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.pref_external_player_diagnostics_view)
                    .setView(scrollView)
                    .setPositiveButton(R.string.pref_external_player_diagnostics_copy,
                            (dialogInterface, which) -> {
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
                            (dialogInterface, which) -> ExternalPlayerDiagnostics.clear(context))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            alertDialog.setOnShowListener(ignored -> scrollView.post(() -> {
                scrollView.fullScroll(View.FOCUS_UP);
                scrollView.requestFocus();
            }));
            alertDialog.show();
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
