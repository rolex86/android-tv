package com.brouken.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Loads a bounded set of subtitle candidates from Stremio's official OpenSubtitles v3 addon. */
final class OpenSubtitlesV3Client {
    static final String TRACK_ID_PREFIX = SmartSubtitleSelector.EXTERNAL_ID_PREFIX
            + "opensubtitles-v3:";
    private static final String BASE_URL = "https://opensubtitles-v3.strem.io/";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SERVER_RESULTS = 500;
    private static final int MAX_LABEL_LENGTH = 140;

    interface Listener {
        void onLoaded(List<MediaItem.SubtitleConfiguration> subtitles);
        void onFailure(String reason);
    }

    static final class Candidate {
        final String url;
        final String language;
        final String label;
        final String mimeType;
        final int roleFlags;
        final int selectionFlags;

        Candidate(String url,
                  String language,
                  String label,
                  String mimeType,
                  int roleFlags,
                  int selectionFlags) {
            this.url = url;
            this.language = language;
            this.label = label;
            this.mimeType = mimeType;
            this.roleFlags = roleFlags;
            this.selectionFlags = selectionFlags;
        }
    }

    private final OkHttpClient httpClient;
    @Nullable private Call activeCall;
    private long operationToken;
    private boolean released;

    OpenSubtitlesV3Client(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    void fetch(String type,
               String id,
               String[] preferredLanguages,
               Listener listener) {
        if (released) {
            return;
        }
        cancel();
        final long token = operationToken;
        if (!isSupportedContent(type, id) || preferredLanguages.length == 0) {
            listener.onLoaded(new ArrayList<>());
            return;
        }

        HttpUrl base = new Request.Builder().url(BASE_URL).build().url();
        HttpUrl url = base.newBuilder()
                .addPathSegment("subtitles")
                .addPathSegment(type)
                .addPathSegment(id + ".json")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus")
                .build();
        Call call = httpClient.newCall(request);
        activeCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call failedCall, @NonNull IOException error) {
                if (isCurrent(token) && !failedCall.isCanceled()) {
                    activeCall = null;
                    listener.onFailure("network");
                }
            }

            @Override
            public void onResponse(@NonNull Call responseCall, @NonNull Response response) {
                try (Response closeable = response) {
                    if (!isCurrent(token)) {
                        return;
                    }
                    if (!closeable.isSuccessful()) {
                        activeCall = null;
                        listener.onFailure("http_" + closeable.code());
                        return;
                    }
                    ResponseBody body = closeable.body();
                    if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                        activeCall = null;
                        listener.onFailure("invalid_body");
                        return;
                    }
                    String json = body.string();
                    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                        activeCall = null;
                        listener.onFailure("response_too_large");
                        return;
                    }
                    List<Candidate> candidates = parseCandidates(json, preferredLanguages);
                    List<MediaItem.SubtitleConfiguration> configurations =
                            buildConfigurations(candidates);
                    activeCall = null;
                    listener.onLoaded(configurations);
                } catch (IOException | JSONException | RuntimeException error) {
                    if (isCurrent(token)) {
                        activeCall = null;
                        listener.onFailure("invalid_response");
                    }
                }
            }
        });
    }

    void cancel() {
        operationToken++;
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    void release() {
        if (released) {
            return;
        }
        cancel();
        released = true;
    }

    private boolean isCurrent(long token) {
        return !released && token == operationToken;
    }

    static boolean isSupportedContent(@Nullable String type, @Nullable String id) {
        if (!("movie".equals(type) || "series".equals(type)) || id == null) {
            return false;
        }
        return id.matches("tt\\d+(?::\\d+:\\d+)?");
    }

    static boolean isOpenSubtitlesTrack(@Nullable String id) {
        return id != null && id.startsWith(TRACK_ID_PREFIX);
    }

    static boolean hasOpenSubtitlesTrack(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int index = 0; index < group.getMediaTrackGroup().length; index++) {
                if (isOpenSubtitlesTrack(group.getMediaTrackGroup().getFormat(index).id)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<Candidate> parseCandidates(String json, String[] preferredLanguages)
            throws JSONException {
        LinkedHashMap<String, Integer> languageRanks = new LinkedHashMap<>();
        for (String preferred : preferredLanguages) {
            String normalized = normalizeLanguage(preferred);
            if (!normalized.isEmpty() && !languageRanks.containsKey(normalized)) {
                languageRanks.put(normalized, languageRanks.size());
            }
        }
        if (languageRanks.isEmpty()) {
            return new ArrayList<>();
        }

        JSONArray subtitles = new JSONObject(json).optJSONArray("subtitles");
        if (subtitles == null) {
            return new ArrayList<>();
        }
        Map<String, Integer> languageCounts = new HashMap<>();
        Set<String> seenUrls = new HashSet<>();
        List<Candidate> selected = new ArrayList<>();
        int length = Math.min(subtitles.length(), MAX_SERVER_RESULTS);
        for (int index = 0; index < length; index++) {
            JSONObject item = subtitles.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String language = normalizeLanguage(item.optString("lang", ""));
            Integer rank = languageRanks.get(language);
            if (rank == null) {
                continue;
            }
            int used = languageCounts.containsKey(language)
                    ? languageCounts.get(language) : 0;
            if (used >= limitForRank(rank)) {
                continue;
            }
            String url = item.optString("url", "").trim();
            if (!isSafeSubtitleUrl(url) || !seenUrls.add(url)) {
                continue;
            }

            String identifier = cleanText(item.optString("id", ""));
            String name = cleanText(item.optString("name", ""));
            String flagsText = (identifier + " " + name).toLowerCase(Locale.ROOT);
            boolean forced = containsAny(flagsText,
                    " forced", "forced ", ".forced", "_forced", "-forced");
            boolean sdh = containsAny(flagsText,
                    " sdh", "sdh ", ".sdh", "_sdh", "-sdh",
                    "hearing impaired", "hard of hearing");
            int roleFlags = C.ROLE_FLAG_SUBTITLE;
            if (sdh) {
                roleFlags |= C.ROLE_FLAG_CAPTION;
            }
            int selectionFlags = forced ? C.SELECTION_FLAG_FORCED : 0;
            String label = buildLabel(language, identifier, name);
            String format = item.optString("format", "").toLowerCase(Locale.ROOT);
            String lowerUrl = url.toLowerCase(Locale.ROOT);
            String mimeType = format.contains("vtt") || lowerUrl.contains(".vtt")
                    ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;

            selected.add(new Candidate(
                    url, language, label, mimeType, roleFlags, selectionFlags));
            languageCounts.put(language, used + 1);
        }
        return selected;
    }

    private static List<MediaItem.SubtitleConfiguration> buildConfigurations(
            List<Candidate> candidates) {
        List<MediaItem.SubtitleConfiguration> configurations = new ArrayList<>();
        for (Candidate candidate : candidates) {
            configurations.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(candidate.url))
                    .setId(TRACK_ID_PREFIX + shortHash(candidate.url))
                    .setLanguage(candidate.language)
                    .setLabel(candidate.label)
                    .setMimeType(candidate.mimeType)
                    .setRoleFlags(candidate.roleFlags)
                    .setSelectionFlags(candidate.selectionFlags)
                    .build());
        }
        return configurations;
    }

    static String normalizeLanguage(@Nullable String language) {
        if (language == null) {
            return "";
        }
        String value = language.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-');
        int separator = value.indexOf('-');
        if (separator > 0) {
            value = value.substring(0, separator);
        }
        switch (value) {
            case "cs":
            case "cze":
            case "ces":
                return "ces";
            case "sk":
            case "slo":
            case "slk":
                return "slk";
            case "en":
            case "eng":
                return "eng";
            case "de":
            case "ger":
            case "deu":
                return "deu";
            case "fr":
            case "fre":
            case "fra":
                return "fra";
            case "es":
            case "spa":
                return "spa";
            case "it":
            case "ita":
                return "ita";
            case "pl":
            case "pol":
                return "pol";
            case "pt":
            case "por":
                return "por";
            case "hu":
            case "hun":
                return "hun";
            case "ru":
            case "rus":
                return "rus";
            case "uk":
            case "ukr":
                return "ukr";
            default:
                return value.matches("[a-z]{2,3}") ? value : "";
        }
    }

    private static int limitForRank(int rank) {
        if (rank <= 1) {
            return 5;
        }
        if (rank == 2) {
            return 3;
        }
        return 2;
    }

    private static boolean isSafeSubtitleUrl(String value) {
        if (value.isEmpty()) {
            return false;
        }
        try {
            HttpUrl url = new Request.Builder().url(value).build().url();
            return "http".equals(url.scheme()) || "https".equals(url.scheme());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String buildLabel(String language, String identifier, String name) {
        StringBuilder label = new StringBuilder("OpenSubtitles v3 · ")
                .append(language.toUpperCase(Locale.ROOT));
        String detail = !name.isEmpty() ? name : identifier;
        if (!detail.isEmpty()) {
            label.append(" · ").append(detail);
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            label.setLength(MAX_LABEL_LENGTH - 1);
            label.append('…');
        }
        return label.toString();
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.replace('\r', ' ')
                .replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... markers) {
        String padded = " " + value + " ";
        for (String marker : markers) {
            if (padded.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
