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
import android.widget.Button;
import android.widget.CheckBox;
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
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.brouken.player.aisubtitles.AiSubtitlePreferences;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;

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
                    PreferenceCategory aggregationCategory = findPreference(
                            StremioAggregationPreferences.KEY_CATEGORY);
                    SwitchPreferenceCompat aggregationEnabled = findPreference(
                            StremioAggregationPreferences.KEY_ENABLED);
                    if (aggregationCategory != null) {
                        aggregationCategory.setVisible(enabled
                                && aggregationEnabled != null
                                && aggregationEnabled.isChecked());
                    }
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

            setupStremioAggregationPreferences(connector);

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

        private void setupStremioAggregationPreferences(
                @Nullable SwitchPreferenceCompat connector) {
            SwitchPreferenceCompat enabled = findPreference(
                    StremioAggregationPreferences.KEY_ENABLED);
            PreferenceCategory category = findPreference(
                    StremioAggregationPreferences.KEY_CATEGORY);
            Preference sourcesPreference = findPreference(
                    StremioAggregationPreferences.KEY_SOURCES);
            Preference resetPreference = findPreference(
                    StremioAggregationPreferences.KEY_RESET);
            if (enabled == null || category == null
                    || sourcesPreference == null || resetPreference == null) {
                return;
            }

            StremioStreamSourceStore sourceStore =
                    new StremioStreamSourceStore(requireContext());
            Runnable refreshSources = () -> {
                List<StremioStreamSourceStore.Source> sources = sourceStore.load();
                int active = 0;
                for (StremioStreamSourceStore.Source source : sources) {
                    if (source.enabled) active++;
                }
                sourcesPreference.setSummary(sources.isEmpty()
                        ? getString(R.string.pref_stremio_sources_empty)
                        : getString(R.string.pref_stremio_sources_count,
                        sources.size(), active));
            };
            Runnable refreshVisibility = () -> category.setVisible(
                    enabled.isChecked() && (connector == null || connector.isChecked()));
            enabled.setOnPreferenceChangeListener((preference, newValue) -> {
                category.setVisible(Boolean.TRUE.equals(newValue)
                        && (connector == null || connector.isChecked()));
                return true;
            });
            sourcesPreference.setOnPreferenceClickListener(preference -> {
                showStreamSourcesDialog(sourceStore, refreshSources);
                return true;
            });
            resetPreference.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.pref_stremio_reset_confirm)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            StremioAggregationPreferences.reset(requireContext());
                            Toast.makeText(requireContext(),
                                    R.string.pref_stremio_reset_done,
                                    Toast.LENGTH_SHORT).show();
                            requireActivity().recreate();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });

            setupAggregationEditFields();
            refreshSources.run();
            refreshVisibility.run();
        }

        private void setupAggregationEditFields() {
            for (String key : new String[]{
                    StremioAggregationPreferences.KEY_MIN_SIZE_GB,
                    StremioAggregationPreferences.KEY_MAX_SIZE_GB}) {
                EditTextPreference preference = findPreference(key);
                if (preference != null) {
                    preference.setOnBindEditTextListener(editText -> editText.setInputType(
                            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
                }
            }
            for (String key : new String[]{
                    StremioAggregationPreferences.KEY_MAX_TOTAL,
                    StremioAggregationPreferences.KEY_MAX_PER_SOURCE,
                    StremioAggregationPreferences.KEY_MAX_PER_QUALITY}) {
                EditTextPreference preference = findPreference(key);
                if (preference != null) {
                    preference.setOnBindEditTextListener(editText -> editText.setInputType(
                            InputType.TYPE_CLASS_NUMBER));
                }
            }
            EditTextPreference blocked = findPreference(
                    StremioAggregationPreferences.KEY_BLOCKED_TEXT);
            if (blocked != null) {
                blocked.setOnBindEditTextListener(editText -> {
                    editText.setSingleLine(false);
                    editText.setMinLines(5);
                    editText.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                });
            }
        }

        private void showStreamSourcesDialog(
                StremioStreamSourceStore store,
                Runnable refreshSummary) {
            Context context = requireContext();
            List<StremioStreamSourceStore.Source> sources = store.load();
            int padding = Math.round(16f * getResources().getDisplayMetrics().density);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(padding, padding / 2, padding, padding / 2);

            final AlertDialog[] holder = new AlertDialog[1];
            for (int index = 0; index < sources.size(); index++) {
                StremioStreamSourceStore.Source source = sources.get(index);
                LinearLayout sourceBlock = new LinearLayout(context);
                sourceBlock.setOrientation(LinearLayout.VERTICAL);
                sourceBlock.setPadding(0, padding / 3, 0, padding / 2);

                CheckBox active = new CheckBox(context);
                active.setText(source.name.isEmpty()
                        ? getString(R.string.pref_stremio_sources) + " " + (index + 1)
                        : source.name);
                active.setChecked(source.enabled);
                final int sourceIndex = index;
                active.setOnCheckedChangeListener((button, checked) -> {
                    List<StremioStreamSourceStore.Source> updated =
                            new ArrayList<>(store.load());
                    if (sourceIndex >= updated.size()) return;
                    StremioStreamSourceStore.Source current = updated.get(sourceIndex);
                    updated.set(sourceIndex, current.withValues(
                            current.manifestUrl, current.name, checked));
                    if (!store.save(updated)) {
                        button.setOnCheckedChangeListener(null);
                        button.setChecked(!checked);
                        Toast.makeText(context,
                                R.string.pref_stremio_source_store_failed,
                                Toast.LENGTH_LONG).show();
                    }
                    refreshSummary.run();
                });
                sourceBlock.addView(active);

                LinearLayout actions = new LinearLayout(context);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                Button up = actionButton(context, R.string.pref_stremio_source_up);
                Button down = actionButton(context, R.string.pref_stremio_source_down);
                Button edit = actionButton(context, R.string.pref_stremio_source_edit);
                up.setEnabled(index > 0);
                down.setEnabled(index + 1 < sources.size());
                up.setOnClickListener(view -> moveSource(
                        store, sourceIndex, -1, holder[0], refreshSummary));
                down.setOnClickListener(view -> moveSource(
                        store, sourceIndex, 1, holder[0], refreshSummary));
                edit.setOnClickListener(view -> {
                    holder[0].dismiss();
                    showEditStreamSourceDialog(store, source, refreshSummary);
                });
                actions.addView(up);
                actions.addView(down);
                actions.addView(edit);
                sourceBlock.addView(actions);
                content.addView(sourceBlock);
            }

            Button add = new Button(context);
            add.setText(R.string.pref_stremio_source_add);
            add.setOnClickListener(view -> {
                holder[0].dismiss();
                showEditStreamSourceDialog(store, null, refreshSummary);
            });
            content.addView(add);

            ScrollView scroll = new ScrollView(context);
            scroll.addView(content);
            holder[0] = new AlertDialog.Builder(context)
                    .setTitle(R.string.pref_stremio_sources_dialog)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            holder[0].show();
        }

        private Button actionButton(Context context, int text) {
            Button button = new Button(context);
            button.setText(text);
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return button;
        }

        private void moveSource(StremioStreamSourceStore store,
                                int index,
                                int delta,
                                AlertDialog dialog,
                                Runnable refreshSummary) {
            List<StremioStreamSourceStore.Source> sources = new ArrayList<>(store.load());
            int target = index + delta;
            if (index < 0 || index >= sources.size() || target < 0 || target >= sources.size()) {
                return;
            }
            Collections.swap(sources, index, target);
            if (!store.save(sources)) {
                Toast.makeText(requireContext(),
                        R.string.pref_stremio_source_store_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            refreshSummary.run();
            dialog.dismiss();
            showStreamSourcesDialog(store, refreshSummary);
        }

        private void showEditStreamSourceDialog(
                StremioStreamSourceStore store,
                @Nullable StremioStreamSourceStore.Source existing,
                Runnable refreshSummary) {
            Context context = requireContext();
            int padding = Math.round(20f * getResources().getDisplayMetrics().density);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(padding, padding / 2, padding, 0);

            EditText name = new EditText(context);
            name.setHint(R.string.pref_stremio_source_name);
            name.setSingleLine(true);
            EditText manifestUrl = new EditText(context);
            manifestUrl.setHint(R.string.pref_stremio_source_url);
            manifestUrl.setSingleLine(true);
            manifestUrl.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_URI);
            CheckBox active = new CheckBox(context);
            active.setText(R.string.pref_stremio_source_enabled);
            active.setChecked(existing == null || existing.enabled);
            if (existing != null) {
                name.setText(existing.name);
                manifestUrl.setText(existing.manifestUrl);
            }
            content.addView(name);
            content.addView(manifestUrl);
            content.addView(active);

            Button delete = new Button(context);
            delete.setText(R.string.pref_stremio_source_delete);
            delete.setVisibility(existing == null ? View.GONE : View.VISIBLE);
            content.addView(delete);

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(existing == null
                            ? R.string.pref_stremio_source_add
                            : R.string.pref_stremio_source_edit)
                    .setView(content)
                    .setPositiveButton(R.string.pref_stremio_source_save, null)
                    .setNeutralButton(R.string.pref_stremio_source_test, null)
                    .setNegativeButton(android.R.string.cancel, (ignored, which) ->
                            showStreamSourcesDialog(store, refreshSummary))
                    .create();
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String url = manifestUrl.getText().toString().trim();
                    HttpUrl parsed = StremioAddonClient.parseManifestUrl(url);
                    if (parsed == null) {
                        Toast.makeText(context,
                                R.string.pref_stremio_source_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String enteredName = name.getText().toString().trim();
                    if (!enteredName.isEmpty()) {
                        persistStreamSource(store, existing, url, enteredName,
                                active.isChecked(), dialog, refreshSummary);
                        return;
                    }
                    inspectStreamSource(url, result -> {
                        if (!result.success) {
                            Toast.makeText(context,
                                    getString(R.string.pref_stremio_source_test_failed,
                                            result.state),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        String detectedName = result.name.isEmpty()
                                ? parsed.host() : result.name;
                        name.setText(detectedName);
                        persistStreamSource(store, existing, url, detectedName,
                                active.isChecked(), dialog, refreshSummary);
                    });
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    String url = manifestUrl.getText().toString().trim();
                    if (StremioAddonClient.parseManifestUrl(url) == null) {
                        Toast.makeText(context,
                                R.string.pref_stremio_source_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    inspectStreamSource(url, result -> {
                        if (result.success) {
                            if (name.getText().toString().trim().isEmpty()
                                    && !result.name.isEmpty()) {
                                name.setText(result.name);
                            }
                            Toast.makeText(context,
                                    getString(R.string.pref_stremio_source_test_ok,
                                            result.name.isEmpty() ? "OK" : result.name),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(context,
                                    getString(R.string.pref_stremio_source_test_failed,
                                            result.state),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                });
                delete.setOnClickListener(view -> {
                    if (existing == null) return;
                    List<StremioStreamSourceStore.Source> sources =
                            new ArrayList<>(store.load());
                    for (int index = sources.size() - 1; index >= 0; index--) {
                        if (sources.get(index).id.equals(existing.id)) {
                            sources.remove(index);
                        }
                    }
                    if (!store.save(sources)) {
                        Toast.makeText(context,
                                R.string.pref_stremio_source_store_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    refreshSummary.run();
                    Toast.makeText(context,
                            R.string.pref_stremio_source_removed,
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    showStreamSourcesDialog(store, refreshSummary);
                });
            });
            dialog.show();
        }

        private void persistStreamSource(
                StremioStreamSourceStore store,
                @Nullable StremioStreamSourceStore.Source existing,
                String manifestUrl,
                String name,
                boolean enabled,
                AlertDialog dialog,
                Runnable refreshSummary) {
            List<StremioStreamSourceStore.Source> sources = new ArrayList<>(store.load());
            if (existing == null) {
                StremioStreamSourceStore.Source created =
                        StremioStreamSourceStore.Source.create(manifestUrl, name)
                                .withValues(manifestUrl, name, enabled);
                sources.add(created);
            } else {
                for (int index = 0; index < sources.size(); index++) {
                    if (sources.get(index).id.equals(existing.id)) {
                        sources.set(index, existing.withValues(manifestUrl, name, enabled));
                        break;
                    }
                }
            }
            if (!store.save(sources)) {
                Toast.makeText(requireContext(),
                        R.string.pref_stremio_source_store_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            refreshSummary.run();
            Toast.makeText(requireContext(),
                    R.string.pref_stremio_source_saved,
                    Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            showStreamSourcesDialog(store, refreshSummary);
        }

        private void inspectStreamSource(
                String manifestUrl,
                ManifestResultListener listener) {
            Toast.makeText(requireContext(),
                    R.string.pref_stremio_source_test_running,
                    Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                OkHttpClient client = StremioAddonClient.newHttpClient();
                StremioAddonClient.ManifestResult result =
                        new StremioAddonClient(client).inspectManifest(manifestUrl);
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (isAdded()) listener.onResult(result);
                });
            }, "stremio-source-test").start();
        }

        private interface ManifestResultListener {
            void onResult(StremioAddonClient.ManifestResult result);
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
