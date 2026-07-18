package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stores semantic track choices rather than unstable Media3 track IDs.
 *
 * The store only influences track selection. It never changes renderers,
 * decoding, audio passthrough, FFmpeg, tunneling or Dolby Vision handling.
 */
final class RememberedTrackStore {
    private static final String PREFS_NAME = "justplayer_plus_track_memory";
    private static final Pattern FILE_EXTENSION = Pattern.compile("\\.[a-z0-9]{2,5}$");
    private static final Pattern BRACKETED_YEAR = Pattern.compile("[\\s._-]*[\\[(](?:19|20)\\d{2}[\\])]\\s*$");
    private static final Pattern EPISODE_MARKER = Pattern.compile(
            "(?i)(?:\\bS\\d{1,2}[ ._-]*E\\d{1,3}\\b|"
                    + "\\b\\d{1,2}[xX]\\d{1,3}\\b|"
                    + "\\bseason[ ._-]*\\d{1,2}[ ._-]*episode[ ._-]*\\d{1,3}\\b|"
                    + "\\bepisode[ ._-]*\\d{1,3}\\b|"
                    + "\\bep[ ._-]*\\d{1,3}\\b)");

    private final SharedPreferences preferences;

    RememberedTrackStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    Selection load(String scope, @Nullable String title, @Nullable Uri mediaUri) {
        String key = buildStorageKey(scope, title, mediaUri);
        if (key == null) {
            return null;
        }
        String json = preferences.getString(key, null);
        if (json == null) {
            return null;
        }
        try {
            return Selection.fromJson(new JSONObject(json));
        } catch (JSONException ignored) {
            preferences.edit().remove(key).apply();
            return null;
        }
    }

    void save(String scope, @Nullable String title, @Nullable Uri mediaUri, Selection selection) {
        String key = buildStorageKey(scope, title, mediaUri);
        if (key == null) {
            return;
        }
        preferences.edit().putString(key, selection.toJson().toString()).apply();
    }

    @Nullable
    private String buildStorageKey(String scope, @Nullable String title, @Nullable Uri mediaUri) {
        if (scope == null || "off".equals(scope)) {
            return null;
        }
        if ("global".equals(scope)) {
            return "tracks.global";
        }

        String source = getIdentitySource(title, mediaUri);
        if (source.isEmpty()) {
            return null;
        }
        if ("series".equals(scope)) {
            source = normalizeSeriesIdentity(source);
        } else {
            source = normalizeIdentity(source);
        }
        if (source.isEmpty()) {
            return null;
        }
        return "tracks." + scope + "." + sha256(source);
    }

    private static String getIdentitySource(@Nullable String title, @Nullable Uri mediaUri) {
        if (title != null && !title.trim().isEmpty()) {
            return title;
        }
        if (mediaUri == null) {
            return "";
        }
        String lastSegment = mediaUri.getLastPathSegment();
        if (lastSegment != null && !lastSegment.trim().isEmpty()) {
            return Uri.decode(lastSegment);
        }
        return mediaUri.toString();
    }

    private static String normalizeSeriesIdentity(String value) {
        String normalized = normalizeIdentity(value);
        Matcher matcher = EPISODE_MARKER.matcher(normalized);
        if (matcher.find()) {
            normalized = normalized.substring(0, matcher.start());
        }
        normalized = BRACKETED_YEAR.matcher(normalized).replaceFirst("");
        return trimSeparators(normalized);
    }

    private static String normalizeIdentity(String value) {
        String normalized = Uri.decode(value);
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        normalized = FILE_EXTENSION.matcher(normalized).replaceFirst("");
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized;
    }

    private static String trimSeparators(String value) {
        return value.replaceAll("^[ ._-]+|[ ._-]+$", "").trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format(Locale.ROOT, "%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    static Selection capture(Tracks tracks, TrackSelectionParameters parameters) {
        Selection selection = new Selection();
        selection.audioAutomatic = !hasOverride(parameters, C.TRACK_TYPE_AUDIO);
        selection.subtitleDisabled = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                || !tracks.isTypeSelected(C.TRACK_TYPE_TEXT);
        selection.subtitleAutomatic = !selection.subtitleDisabled
                && !hasOverride(parameters, C.TRACK_TYPE_TEXT);

        Format audio = findSelectedFormat(tracks, C.TRACK_TYPE_AUDIO);
        if (audio != null) {
            selection.audioLanguage = safe(audio.language);
            selection.audioMime = safe(audio.sampleMimeType);
            selection.audioChannels = audio.channelCount;
            selection.audioRole = audioRole(audio);
            selection.audioLabel = normalizeLabel(audio.label);
        }

        Format subtitle = findSelectedFormat(tracks, C.TRACK_TYPE_TEXT);
        if (subtitle != null) {
            selection.subtitleLanguage = safe(subtitle.language);
            selection.subtitleMime = safe(subtitle.sampleMimeType);
            selection.subtitleForced = isForcedSubtitle(subtitle);
            selection.subtitleLabel = normalizeLabel(subtitle.label);
        }
        return selection;
    }

    @Nullable
    static TrackSelectionOverride findAudioOverride(Tracks tracks, Selection remembered) {
        if (remembered.audioAutomatic) {
            return null;
        }
        Candidate best = null;
        int order = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int i = 0; i < trackGroup.length; i++) {
                if (!group.isTrackSupported(i)) {
                    order++;
                    continue;
                }
                Format format = trackGroup.getFormat(i);
                if (!languageMatches(remembered.audioLanguage, format.language)) {
                    order++;
                    continue;
                }
                if (!remembered.audioRole.isEmpty()
                        && !remembered.audioRole.equals(audioRole(format))) {
                    order++;
                    continue;
                }
                long score = order++;
                if (!remembered.audioLabel.isEmpty()
                        && !remembered.audioLabel.equals(normalizeLabel(format.label))) {
                    score += 10_000L;
                }
                if (!remembered.audioMime.isEmpty()
                        && !remembered.audioMime.equals(safe(format.sampleMimeType))) {
                    score += 1_000L;
                }
                if (remembered.audioChannels > 0 && format.channelCount > 0) {
                    score += Math.abs(remembered.audioChannels - format.channelCount) * 100L;
                }
                Candidate candidate = new Candidate(trackGroup, i, score);
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }
        return best == null ? null : best.toOverride();
    }

    @Nullable
    static TrackSelectionOverride findSubtitleOverride(Tracks tracks, Selection remembered) {
        if (remembered.subtitleDisabled || remembered.subtitleAutomatic) {
            return null;
        }
        Candidate best = null;
        int order = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int i = 0; i < trackGroup.length; i++) {
                if (!group.isTrackSupported(i)) {
                    order++;
                    continue;
                }
                Format format = trackGroup.getFormat(i);
                if (!languageMatches(remembered.subtitleLanguage, format.language)) {
                    order++;
                    continue;
                }
                if (remembered.subtitleForced != isForcedSubtitle(format)) {
                    order++;
                    continue;
                }
                long score = order++;
                if (!remembered.subtitleLabel.isEmpty()
                        && !remembered.subtitleLabel.equals(normalizeLabel(format.label))) {
                    score += 10_000L;
                }
                if (!remembered.subtitleMime.isEmpty()
                        && !remembered.subtitleMime.equals(safe(format.sampleMimeType))) {
                    score += 1_000L;
                }
                Candidate candidate = new Candidate(trackGroup, i, score);
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }
        return best == null ? null : best.toOverride();
    }

    @Nullable
    private static Format findSelectedFormat(Tracks tracks, int trackType) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int i = 0; i < trackGroup.length; i++) {
                if (group.isTrackSelected(i)) {
                    return trackGroup.getFormat(i);
                }
            }
        }
        return null;
    }

    private static boolean hasOverride(TrackSelectionParameters parameters, int trackType) {
        for (TrackSelectionOverride override : parameters.overrides.values()) {
            if (override.getType() == trackType) {
                return true;
            }
        }
        return false;
    }

    private static boolean languageMatches(String remembered, @Nullable String candidate) {
        if (remembered.isEmpty() || "und".equalsIgnoreCase(remembered)) {
            return candidate == null || candidate.isEmpty() || "und".equalsIgnoreCase(candidate);
        }
        return normalizeLanguage(remembered).equals(normalizeLanguage(candidate));
    }

    private static String normalizeLanguage(@Nullable String language) {
        if (language == null || language.isEmpty()) {
            return "";
        }
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        if (locale.getLanguage().isEmpty()) {
            locale = new Locale(language);
        }
        try {
            return locale.getISO3Language().toLowerCase(Locale.ROOT);
        } catch (MissingResourceException ignored) {
            return locale.getLanguage().toLowerCase(Locale.ROOT);
        }
    }

    private static String audioRole(Format format) {
        if ((format.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0
                || contains(format.label, "commentary")
                || contains(format.label, "komentar")) {
            return "commentary";
        }
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0
                || contains(format.label, "audio description")
                || contains(format.label, "descriptive audio")
                || contains(format.label, "audiodeskripce")) {
            return "description";
        }
        return "main";
    }

    private static boolean isForcedSubtitle(Format format) {
        return (format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                || contains(format.label, "forced")
                || contains(format.label, "vynucene");
    }

    private static boolean contains(@Nullable String value, String token) {
        return normalizeLabel(value).contains(normalizeLabel(token));
    }

    private static String normalizeLabel(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    static final class Selection {
        boolean audioAutomatic = true;
        String audioLanguage = "";
        String audioMime = "";
        int audioChannels = Format.NO_VALUE;
        String audioRole = "main";
        String audioLabel = "";

        boolean subtitleDisabled = true;
        boolean subtitleAutomatic = false;
        String subtitleLanguage = "";
        String subtitleMime = "";
        boolean subtitleForced = false;
        String subtitleLabel = "";

        String signature() {
            return audioAutomatic + "|" + audioLanguage + "|" + audioMime + "|"
                    + audioChannels + "|" + audioRole + "|" + audioLabel + "||"
                    + subtitleDisabled + "|" + subtitleAutomatic + "|"
                    + subtitleLanguage + "|" + subtitleMime + "|"
                    + subtitleForced + "|" + subtitleLabel;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("audioAutomatic", audioAutomatic);
                json.put("audioLanguage", audioLanguage);
                json.put("audioMime", audioMime);
                json.put("audioChannels", audioChannels);
                json.put("audioRole", audioRole);
                json.put("audioLabel", audioLabel);
                json.put("subtitleDisabled", subtitleDisabled);
                json.put("subtitleAutomatic", subtitleAutomatic);
                json.put("subtitleLanguage", subtitleLanguage);
                json.put("subtitleMime", subtitleMime);
                json.put("subtitleForced", subtitleForced);
                json.put("subtitleLabel", subtitleLabel);
            } catch (JSONException ignored) {
                // Keys and primitive values used here cannot fail in normal operation.
            }
            return json;
        }

        static Selection fromJson(JSONObject json) {
            Selection selection = new Selection();
            selection.audioAutomatic = json.optBoolean("audioAutomatic", true);
            selection.audioLanguage = json.optString("audioLanguage", "");
            selection.audioMime = json.optString("audioMime", "");
            selection.audioChannels = json.optInt("audioChannels", Format.NO_VALUE);
            selection.audioRole = json.optString("audioRole", "main");
            selection.audioLabel = json.optString("audioLabel", "");
            selection.subtitleDisabled = json.optBoolean("subtitleDisabled", true);
            selection.subtitleAutomatic = json.optBoolean("subtitleAutomatic", false);
            selection.subtitleLanguage = json.optString("subtitleLanguage", "");
            selection.subtitleMime = json.optString("subtitleMime", "");
            selection.subtitleForced = json.optBoolean("subtitleForced", false);
            selection.subtitleLabel = json.optString("subtitleLabel", "");
            return selection;
        }
    }

    private static final class Candidate {
        final TrackGroup trackGroup;
        final int trackIndex;
        final long score;

        Candidate(TrackGroup trackGroup, int trackIndex, long score) {
            this.trackGroup = trackGroup;
            this.trackIndex = trackIndex;
            this.score = score;
        }

        TrackSelectionOverride toOverride() {
            return new TrackSelectionOverride(
                    trackGroup, Collections.singletonList(trackIndex));
        }
    }
}
