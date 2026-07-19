package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.LinkedHashSet;

/**
 * Preferences added by JustPlayer Plus.
 *
 * This class deliberately contains no player or renderer code. It only exposes
 * persisted user choices so each later feature can be implemented and reverted
 * independently without changing the original audio/video pipeline.
 */
class PlusPrefs {
    static final String TRACK_NONE = "none";

    static final String KEY_AUDIO_LANGUAGE_PRIMARY = "languageAudio";
    static final String KEY_AUDIO_LANGUAGE_SECONDARY = "languageAudioSecondary";
    static final String KEY_AUDIO_LANGUAGE_TERTIARY = "languageAudioTertiary";
    static final String KEY_AUDIO_QUALITY = "audioQualityPreference";
    static final String KEY_AUDIO_CONTENT_PREFERENCE = "audioContentPreference";
    static final String KEY_IGNORE_COMMENTARY_AUDIO = "ignoreCommentaryAudio";
    static final String KEY_IGNORE_AUDIO_DESCRIPTION = "ignoreAudioDescription";

    static final String KEY_SUBTITLE_LANGUAGE_PRIMARY = "languageSubtitlePrimary";
    static final String KEY_SUBTITLE_LANGUAGE_SECONDARY = "languageSubtitleSecondary";
    static final String KEY_SUBTITLE_LANGUAGE_TERTIARY = "languageSubtitleTertiary";
    static final String KEY_SUBTITLE_MODE = "subtitleMode";
    static final String KEY_PREFER_FORCED_SUBTITLES = "preferForcedSubtitles";
    static final String KEY_ALLOW_UNKNOWN_SUBTITLES = "allowUnknownSubtitles";
    static final String KEY_IGNORE_SDH_SUBTITLES = "ignoreSdhSubtitles";
    static final String KEY_SUBTITLE_SOURCE = "subtitleSourcePreference";
    static final String KEY_SUBTITLE_DELAY_MS = "subtitleDelayMs";
    static final String KEY_SUBTITLE_SCALE = "subtitleScale";
    static final String KEY_SUBTITLE_POSITION = "subtitlePosition";

    static final String KEY_REMEMBER_TRACK_SCOPE = "rememberTrackScope";
    static final String KEY_RESIZE_DEFAULT = "resizeDefault";
    static final String KEY_SPEED_DEFAULT = "speedDefault";
    static final String KEY_SEEK_INCREMENT_MS = "seekIncrementMs";
    static final String KEY_FRAME_RATE_POLICY = "frameRatePolicy";

    static final String KEY_BACK_BUTTON_BEHAVIOR = "backButtonBehavior";
    static final String KEY_COMPLETION_RULE = "completionRule";
    static final String KEY_EXTERNAL_PLAYER_DIAGNOSTICS = "externalPlayerDiagnostics";

    private final SharedPreferences preferences;

    String audioLanguagePrimary;
    String audioLanguageSecondary;
    String audioLanguageTertiary;
    String audioQualityPreference;
    String audioContentPreference;
    boolean ignoreCommentaryAudio;
    boolean ignoreAudioDescription;

    String subtitleLanguagePrimary;
    String subtitleLanguageSecondary;
    String subtitleLanguageTertiary;
    String subtitleMode;
    boolean preferForcedSubtitles;
    boolean allowUnknownSubtitles;
    boolean ignoreSdhSubtitles;
    String subtitleSourcePreference;
    int subtitleDelayMs;
    String subtitleScale;
    String subtitlePosition;

    String rememberTrackScope;
    String resizeDefault;
    String speedDefault;
    long seekIncrementMs;
    String frameRatePolicy;

    String backButtonBehavior;
    String completionRule;
    boolean externalPlayerDiagnostics;

    PlusPrefs(Context context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        reload();
    }

    void reload() {
        audioLanguagePrimary = preferences.getString(
                KEY_AUDIO_LANGUAGE_PRIMARY, Prefs.TRACK_DEVICE);
        audioLanguageSecondary = preferences.getString(
                KEY_AUDIO_LANGUAGE_SECONDARY, "eng");
        audioLanguageTertiary = preferences.getString(
                KEY_AUDIO_LANGUAGE_TERTIARY, TRACK_NONE);
        audioQualityPreference = preferences.getString(KEY_AUDIO_QUALITY, "best");
        audioContentPreference = preferences.getString(
                KEY_AUDIO_CONTENT_PREFERENCE, "language");
        ignoreCommentaryAudio = preferences.getBoolean(KEY_IGNORE_COMMENTARY_AUDIO, true);
        ignoreAudioDescription = preferences.getBoolean(KEY_IGNORE_AUDIO_DESCRIPTION, true);

        subtitleLanguagePrimary = preferences.getString(
                KEY_SUBTITLE_LANGUAGE_PRIMARY, "ces");
        subtitleLanguageSecondary = preferences.getString(
                KEY_SUBTITLE_LANGUAGE_SECONDARY, "slk");
        subtitleLanguageTertiary = preferences.getString(
                KEY_SUBTITLE_LANGUAGE_TERTIARY, "eng");
        subtitleMode = preferences.getString(KEY_SUBTITLE_MODE, "foreign_audio");
        preferForcedSubtitles = preferences.getBoolean(KEY_PREFER_FORCED_SUBTITLES, true);
        allowUnknownSubtitles = preferences.getBoolean(KEY_ALLOW_UNKNOWN_SUBTITLES, true);
        ignoreSdhSubtitles = preferences.getBoolean(KEY_IGNORE_SDH_SUBTITLES, true);
        subtitleSourcePreference = preferences.getString(KEY_SUBTITLE_SOURCE, "auto");
        subtitleDelayMs = parseInt(KEY_SUBTITLE_DELAY_MS, 0);
        subtitleScale = preferences.getString(KEY_SUBTITLE_SCALE, "system");
        subtitlePosition = preferences.getString(KEY_SUBTITLE_POSITION, "system");

        rememberTrackScope = preferences.getString(KEY_REMEMBER_TRACK_SCOPE, "series");
        resizeDefault = preferences.getString(KEY_RESIZE_DEFAULT, "remember");
        speedDefault = preferences.getString(KEY_SPEED_DEFAULT, "remember");
        seekIncrementMs = parseLong(KEY_SEEK_INCREMENT_MS, 10_000L);
        frameRatePolicy = preferences.getString(KEY_FRAME_RATE_POLICY, "legacy");

        backButtonBehavior = preferences.getString(
                KEY_BACK_BUTTON_BEHAVIOR, "controls_then_exit");
        completionRule = preferences.getString(KEY_COMPLETION_RULE, "percent_95");
        externalPlayerDiagnostics = preferences.getBoolean(
                KEY_EXTERNAL_PLAYER_DIAGNOSTICS, false);
    }

    String[] getPreferredAudioLanguages() {
        LinkedHashSet<String> languages = new LinkedHashSet<>();

        if (!appendAudioLanguagePreference(languages, audioLanguagePrimary)) {
            return languages.toArray(new String[0]);
        }
        if (!appendAudioLanguagePreference(languages, audioLanguageSecondary)) {
            return languages.toArray(new String[0]);
        }
        appendAudioLanguagePreference(languages, audioLanguageTertiary);

        return languages.toArray(new String[0]);
    }

    private boolean appendAudioLanguagePreference(LinkedHashSet<String> languages,
                                                   String preference) {
        if (preference == null || TRACK_NONE.equals(preference)) {
            return true;
        }
        if (Prefs.TRACK_DEFAULT.equals(preference)) {
            // Media default acts as the fallback point in the ordered list.
            return false;
        }
        if (Prefs.TRACK_DEVICE.equals(preference)) {
            for (String language : Utils.getDeviceLanguages()) {
                if (language != null && !language.isEmpty()) {
                    languages.add(language);
                }
            }
        } else if (!preference.isEmpty()) {
            languages.add(preference);
        }
        return true;
    }

    String[] getPreferredSubtitleLanguages() {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        appendSubtitleLanguagePreference(languages, subtitleLanguagePrimary);
        appendSubtitleLanguagePreference(languages, subtitleLanguageSecondary);
        appendSubtitleLanguagePreference(languages, subtitleLanguageTertiary);
        return languages.toArray(new String[0]);
    }

    boolean useMediaDefaultSubtitleFallback() {
        return Prefs.TRACK_DEFAULT.equals(subtitleLanguagePrimary)
                || Prefs.TRACK_DEFAULT.equals(subtitleLanguageSecondary)
                || Prefs.TRACK_DEFAULT.equals(subtitleLanguageTertiary);
    }

    private void appendSubtitleLanguagePreference(LinkedHashSet<String> languages,
                                                   String preference) {
        if (preference == null || preference.isEmpty()
                || TRACK_NONE.equals(preference) || Prefs.TRACK_DEFAULT.equals(preference)) {
            return;
        }
        if (Prefs.TRACK_DEVICE.equals(preference)) {
            for (String language : Utils.getDeviceLanguages()) {
                if (language != null && !language.isEmpty()) {
                    languages.add(language);
                }
            }
        } else {
            languages.add(preference);
        }
    }

    private int parseInt(String key, int fallback) {
        try {
            return Integer.parseInt(preferences.getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String key, long fallback) {
        try {
            return Long.parseLong(preferences.getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
