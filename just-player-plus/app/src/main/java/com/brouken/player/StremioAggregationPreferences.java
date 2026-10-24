package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Non-sensitive aggregation preferences. Manifest URLs live in StremioStreamSourceStore. */
final class StremioAggregationPreferences {
    static final String KEY_ENABLED = "stremioAggregationEnabled";
    static final String KEY_CATEGORY = "stremioAggregationCategory";
    static final String KEY_SOURCES = "stremioAggregationSources";
    static final String KEY_SOURCE_WAIT_SECONDS = "stremioAggregationSourceWaitSeconds";
    static final String KEY_SORT_MODE = "stremioAggregationSortMode";
    static final String KEY_PREFER_CACHED = "stremioAggregationPreferCached";
    static final String KEY_SIZE_SORT = "stremioAggregationSizeSort";
    static final String KEY_PREFERRED_LANGUAGES = "stremioAggregationPreferredLanguages";
    static final String KEY_RESOLUTIONS = "stremioAggregationResolutions";
    static final String KEY_STREAM_TYPES = "stremioAggregationStreamTypes";
    static final String KEY_BLOCKED_RELEASES = "stremioAggregationBlockedReleases";
    static final String KEY_CODECS = "stremioAggregationCodecs";
    static final String KEY_HDR_FORMATS = "stremioAggregationHdrFormats";
    static final String KEY_KEEP_UNKNOWN_TECH = "stremioAggregationKeepUnknownTech";
    static final String KEY_ALLOWED_LANGUAGES = "stremioAggregationAllowedLanguages";
    static final String KEY_KEEP_UNKNOWN_LANGUAGE = "stremioAggregationKeepUnknownLanguage";
    static final String KEY_MIN_SIZE_GB = "stremioAggregationMinSizeGb";
    static final String KEY_MAX_SIZE_GB = "stremioAggregationMaxSizeGb";
    static final String KEY_KEEP_UNKNOWN_SIZE = "stremioAggregationKeepUnknownSize";
    static final String KEY_BLOCKED_TEXT = "stremioAggregationBlockedText";
    static final String KEY_MAX_TOTAL = "stremioAggregationMaxTotal";
    static final String KEY_MAX_PER_SOURCE = "stremioAggregationMaxPerSource";
    static final String KEY_MAX_PER_QUALITY = "stremioAggregationMaxPerQuality";
    static final String KEY_DEDUPLICATION = "stremioAggregationDeduplication";
    static final String KEY_BINGE_GROUP = "stremioAggregationBingeGroup";
    static final String KEY_DISPLAY_FIELDS = "stremioAggregationDisplayFields";
    static final String KEY_RESET = "stremioAggregationReset";

    static final int MIN_SOURCE_WAIT_SECONDS = 3;
    static final int MAX_SOURCE_WAIT_SECONDS = 30;
    static final int DEFAULT_SOURCE_WAIT_SECONDS = 9;

    static final Set<String> DEFAULT_RESOLUTIONS = setOf(
            "2160p", "1080p", "720p", "sd", "unknown");
    static final Set<String> DEFAULT_STREAM_TYPES = setOf(
            "direct", "torrent", "external");
    static final Set<String> DEFAULT_CODECS = setOf("h264", "hevc", "av1", "other");
    static final Set<String> DEFAULT_HDR_FORMATS = setOf("sdr", "hdr", "dv");
    static final Set<String> DEFAULT_DISPLAY_FIELDS = setOf(
            "resolution", "language", "codec", "hdr", "audio", "size",
            "cached", "filename", "original");

    private StremioAggregationPreferences() {
    }

    static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_ENABLED, false);
    }

    static Snapshot read(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return new Builder()
                .setSourceWaitSeconds(preferences.getInt(
                        KEY_SOURCE_WAIT_SECONDS, DEFAULT_SOURCE_WAIT_SECONDS))
                .setSortMode(preferences.getString(KEY_SORT_MODE, "quality_interleaved"))
                .setPreferCached(preferences.getBoolean(KEY_PREFER_CACHED, false))
                .setSizeSort(preferences.getString(KEY_SIZE_SORT, "none"))
                .setPreferredLanguages(parseList(
                        preferences.getString(KEY_PREFERRED_LANGUAGES, "")))
                .setResolutions(copySet(preferences.getStringSet(
                        KEY_RESOLUTIONS, DEFAULT_RESOLUTIONS), DEFAULT_RESOLUTIONS))
                .setStreamTypes(copySet(preferences.getStringSet(
                        KEY_STREAM_TYPES, DEFAULT_STREAM_TYPES), DEFAULT_STREAM_TYPES))
                .setBlockedReleases(copySet(preferences.getStringSet(
                        KEY_BLOCKED_RELEASES, Collections.emptySet()),
                        Collections.emptySet()))
                .setCodecs(copySet(preferences.getStringSet(
                        KEY_CODECS, DEFAULT_CODECS), DEFAULT_CODECS))
                .setHdrFormats(copySet(preferences.getStringSet(
                        KEY_HDR_FORMATS, DEFAULT_HDR_FORMATS), DEFAULT_HDR_FORMATS))
                .setKeepUnknownTech(preferences.getBoolean(KEY_KEEP_UNKNOWN_TECH, true))
                .setAllowedLanguages(parseList(
                        preferences.getString(KEY_ALLOWED_LANGUAGES, "")))
                .setKeepUnknownLanguage(preferences.getBoolean(
                        KEY_KEEP_UNKNOWN_LANGUAGE, true))
                .setMinSizeGb(parseDouble(preferences.getString(KEY_MIN_SIZE_GB, "0"), 0d))
                .setMaxSizeGb(parseDouble(preferences.getString(KEY_MAX_SIZE_GB, "0"), 0d))
                .setKeepUnknownSize(preferences.getBoolean(KEY_KEEP_UNKNOWN_SIZE, true))
                .setBlockedText(parseLines(preferences.getString(KEY_BLOCKED_TEXT, "")))
                .setMaxTotal(parseInt(preferences.getString(KEY_MAX_TOTAL, "100"), 100, 0, 500))
                .setMaxPerSource(parseInt(
                        preferences.getString(KEY_MAX_PER_SOURCE, "30"), 30, 0, 250))
                .setMaxPerQuality(parseInt(
                        preferences.getString(KEY_MAX_PER_QUALITY, "0"), 0, 0, 250))
                .setDeduplication(preferences.getString(KEY_DEDUPLICATION, "safe"))
                .setBingeGroup(preferences.getString(KEY_BINGE_GROUP, "quality"))
                .setDisplayFields(copySet(preferences.getStringSet(
                        KEY_DISPLAY_FIELDS, DEFAULT_DISPLAY_FIELDS), DEFAULT_DISPLAY_FIELDS))
                .build();
    }

    static void reset(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(KEY_SOURCE_WAIT_SECONDS)
                .remove(KEY_SORT_MODE)
                .remove(KEY_PREFER_CACHED)
                .remove(KEY_SIZE_SORT)
                .remove(KEY_PREFERRED_LANGUAGES)
                .remove(KEY_RESOLUTIONS)
                .remove(KEY_STREAM_TYPES)
                .remove(KEY_BLOCKED_RELEASES)
                .remove(KEY_CODECS)
                .remove(KEY_HDR_FORMATS)
                .remove(KEY_KEEP_UNKNOWN_TECH)
                .remove(KEY_ALLOWED_LANGUAGES)
                .remove(KEY_KEEP_UNKNOWN_LANGUAGE)
                .remove(KEY_MIN_SIZE_GB)
                .remove(KEY_MAX_SIZE_GB)
                .remove(KEY_KEEP_UNKNOWN_SIZE)
                .remove(KEY_BLOCKED_TEXT)
                .remove(KEY_MAX_TOTAL)
                .remove(KEY_MAX_PER_SOURCE)
                .remove(KEY_MAX_PER_QUALITY)
                .remove(KEY_DEDUPLICATION)
                .remove(KEY_BINGE_GROUP)
                .remove(KEY_DISPLAY_FIELDS)
                .apply();
    }

    private static int parseInt(String value, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return !Double.isNaN(parsed) && !Double.isInfinite(parsed) && parsed >= 0d
                    ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Set<String> parseList(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        for (String item : value.split("[,;\\s]+")) {
            String normalized = StremioStreamPipeline.normalizeLanguage(item);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static Set<String> parseLines(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        for (String item : value.split("[\\r\\n]+")) {
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && normalized.length() <= 120) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static Set<String> copySet(Set<String> value, Set<String> fallback) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(value == null ? fallback : value));
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    static final class Snapshot {
        final int sourceWaitSeconds;
        final String sortMode;
        final boolean preferCached;
        final String sizeSort;
        final Set<String> preferredLanguages;
        final Set<String> resolutions;
        final Set<String> streamTypes;
        final Set<String> blockedReleases;
        final Set<String> codecs;
        final Set<String> hdrFormats;
        final boolean keepUnknownTech;
        final Set<String> allowedLanguages;
        final boolean keepUnknownLanguage;
        final double minSizeGb;
        final double maxSizeGb;
        final boolean keepUnknownSize;
        final Set<String> blockedText;
        final int maxTotal;
        final int maxPerSource;
        final int maxPerQuality;
        final String deduplication;
        final String bingeGroup;
        final Set<String> displayFields;

        Snapshot(Builder builder) {
            sourceWaitSeconds = builder.sourceWaitSeconds;
            sortMode = builder.sortMode;
            preferCached = builder.preferCached;
            sizeSort = builder.sizeSort;
            preferredLanguages = immutable(builder.preferredLanguages);
            resolutions = immutable(builder.resolutions);
            streamTypes = immutable(builder.streamTypes);
            blockedReleases = immutable(builder.blockedReleases);
            codecs = immutable(builder.codecs);
            hdrFormats = immutable(builder.hdrFormats);
            keepUnknownTech = builder.keepUnknownTech;
            allowedLanguages = immutable(builder.allowedLanguages);
            keepUnknownLanguage = builder.keepUnknownLanguage;
            minSizeGb = builder.minSizeGb;
            maxSizeGb = builder.maxSizeGb;
            keepUnknownSize = builder.keepUnknownSize;
            blockedText = immutable(builder.blockedText);
            maxTotal = builder.maxTotal;
            maxPerSource = builder.maxPerSource;
            maxPerQuality = builder.maxPerQuality;
            deduplication = builder.deduplication;
            bingeGroup = builder.bingeGroup;
            displayFields = immutable(builder.displayFields);
        }

        String cacheKey() {
            return sourceWaitSeconds + "s|" + sortMode + '|'
                    + preferCached + '|' + sizeSort + '|'
                    + preferredLanguages + '|' + resolutions + '|' + streamTypes + '|'
                    + blockedReleases + '|' + codecs + '|' + hdrFormats + '|'
                    + keepUnknownTech + '|' + allowedLanguages + '|'
                    + keepUnknownLanguage + '|' + minSizeGb + '|' + maxSizeGb + '|'
                    + keepUnknownSize + '|' + blockedText + '|' + maxTotal + '|'
                    + maxPerSource + '|' + maxPerQuality + '|' + deduplication + '|'
                    + bingeGroup + '|' + displayFields;
        }

        long sourceWaitMs() {
            return TimeUnit.SECONDS.toMillis(sourceWaitSeconds);
        }

        private static Set<String> immutable(Set<String> value) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(value));
        }
    }

    static final class Builder {
        private int sourceWaitSeconds = DEFAULT_SOURCE_WAIT_SECONDS;
        private String sortMode = "quality_interleaved";
        private boolean preferCached;
        private String sizeSort = "none";
        private Set<String> preferredLanguages = Collections.emptySet();
        private Set<String> resolutions = DEFAULT_RESOLUTIONS;
        private Set<String> streamTypes = DEFAULT_STREAM_TYPES;
        private Set<String> blockedReleases = Collections.emptySet();
        private Set<String> codecs = DEFAULT_CODECS;
        private Set<String> hdrFormats = DEFAULT_HDR_FORMATS;
        private boolean keepUnknownTech = true;
        private Set<String> allowedLanguages = Collections.emptySet();
        private boolean keepUnknownLanguage = true;
        private double minSizeGb;
        private double maxSizeGb;
        private boolean keepUnknownSize = true;
        private Set<String> blockedText = Collections.emptySet();
        private int maxTotal = 100;
        private int maxPerSource = 30;
        private int maxPerQuality;
        private String deduplication = "safe";
        private String bingeGroup = "quality";
        private Set<String> displayFields = DEFAULT_DISPLAY_FIELDS;

        Builder setSourceWaitSeconds(int value) {
            sourceWaitSeconds = clampSourceWaitSeconds(value);
            return this;
        }
        Builder setSortMode(String value) { sortMode = safe(value, "quality_interleaved"); return this; }
        Builder setPreferCached(boolean value) { preferCached = value; return this; }
        Builder setSizeSort(String value) { sizeSort = safe(value, "none"); return this; }
        Builder setPreferredLanguages(Set<String> value) { preferredLanguages = value; return this; }
        Builder setResolutions(Set<String> value) { resolutions = value; return this; }
        Builder setStreamTypes(Set<String> value) { streamTypes = value; return this; }
        Builder setBlockedReleases(Set<String> value) { blockedReleases = value; return this; }
        Builder setCodecs(Set<String> value) { codecs = value; return this; }
        Builder setHdrFormats(Set<String> value) { hdrFormats = value; return this; }
        Builder setKeepUnknownTech(boolean value) { keepUnknownTech = value; return this; }
        Builder setAllowedLanguages(Set<String> value) { allowedLanguages = value; return this; }
        Builder setKeepUnknownLanguage(boolean value) { keepUnknownLanguage = value; return this; }
        Builder setMinSizeGb(double value) { minSizeGb = Math.max(0d, value); return this; }
        Builder setMaxSizeGb(double value) { maxSizeGb = Math.max(0d, value); return this; }
        Builder setKeepUnknownSize(boolean value) { keepUnknownSize = value; return this; }
        Builder setBlockedText(Set<String> value) { blockedText = value; return this; }
        Builder setMaxTotal(int value) { maxTotal = Math.max(0, value); return this; }
        Builder setMaxPerSource(int value) { maxPerSource = Math.max(0, value); return this; }
        Builder setMaxPerQuality(int value) { maxPerQuality = Math.max(0, value); return this; }
        Builder setDeduplication(String value) { deduplication = safe(value, "safe"); return this; }
        Builder setBingeGroup(String value) { bingeGroup = safe(value, "quality"); return this; }
        Builder setDisplayFields(Set<String> value) { displayFields = value; return this; }
        Snapshot build() { return new Snapshot(this); }

        private static String safe(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }
    }

    static int clampSourceWaitSeconds(int value) {
        return Math.max(MIN_SOURCE_WAIT_SECONDS, Math.min(MAX_SOURCE_WAIT_SECONDS, value));
    }
}
