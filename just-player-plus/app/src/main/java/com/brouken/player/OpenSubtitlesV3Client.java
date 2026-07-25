package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;

import com.brouken.player.aisubtitles.SubtitleTrackIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Loads OpenSubtitles immediately, then refines confidence in the background. */
final class OpenSubtitlesV3Client {
    static final String TRACK_ID_PREFIX = SmartSubtitleSelector.EXTERNAL_ID_PREFIX
            + "opensubtitles-v3:";
    private static final String BASE_URL = "https://opensubtitles-v3.strem.io/";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SERVER_RESULTS = 500;
    private static final int MAX_LABEL_LENGTH = 140;

    enum MatchConfidence {
        EXACT(0), LIKELY(1), UNKNOWN(2);
        final int rank;
        MatchConfidence(int rank) {
            this.rank = rank;
        }
    }

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
        final MatchConfidence confidence;
        final int languageRank;
        final int sourceOrder;

        Candidate(String url, String language, String label, String mimeType,
                  int roleFlags, int selectionFlags, MatchConfidence confidence,
                  int languageRank, int sourceOrder) {
            this.url = url;
            this.language = language;
            this.label = label;
            this.mimeType = mimeType;
            this.roleFlags = roleFlags;
            this.selectionFlags = selectionFlags;
            this.confidence = confidence;
            this.languageRank = languageRank;
            this.sourceOrder = sourceOrder;
        }
    }

    private final OkHttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @Nullable private volatile Call activeCall;
    @Nullable private volatile Future<?> activeTask;
    private volatile long operationToken;
    private volatile boolean released;

    OpenSubtitlesV3Client(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    void fetch(String type, String id, String[] preferredLanguages, Listener listener) {
        if (released) return;
        cancel();
        SubtitleTrackIdentity.resetOpenSubtitlesMatches();
        long token = operationToken;
        String[] languages = requestedLanguages(preferredLanguages);
        if (!isSupportedContent(type, id) || languages.length == 0) {
            listener.onLoaded(new ArrayList<>());
            return;
        }
        activeTask = executor.submit(() -> fetchInBackground(token, type, id, languages, listener));
    }

    private static String[] requestedLanguages(String[] preferred) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String language : preferred) addLanguage(result, language);
        if (PlayerActivity.player != null) {
            for (Tracks.Group group : PlayerActivity.player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.getMediaTrackGroup().length; i++) {
                    addLanguage(result, group.getMediaTrackGroup().getFormat(i).language);
                }
            }
        }
        return result.toArray(new String[0]);
    }

    private static void addLanguage(Set<String> target, @Nullable String language) {
        String normalized = normalizeLanguage(language);
        if (!normalized.isEmpty() && !"und".equals(normalized)) target.add(normalized);
    }

    private void fetchInBackground(long token, String type, String id,
                                   String[] languages, Listener listener) {
        LinkedHashMap<String, Candidate> merged = new LinkedHashMap<>();
        String filename = currentFilename();

        // Preserve the old behavior: generic IMDb results are loaded and attached first.
        try {
            merge(merged, parseCandidatesInternal(
                    requestJson(token, genericUrl(type, id)), languages,
                    MatchConfidence.UNKNOWN, filename));
        } catch (IOException | JSONException | RuntimeException error) {
            if (isCurrent(token)) {
                clearTask(token);
                listener.onFailure(error instanceof IOException ? "network" : "invalid_response");
            }
            return;
        }

        if (!isCurrent(token)) return;
        List<MediaItem.SubtitleConfiguration> immediate = buildConfigurations(
                sortAndLimit(new ArrayList<>(merged.values())));
        listener.onLoaded(immediate);

        // Hashing is a second-stage refinement and never blocks the initial list.
        OpenSubtitlesMediaFingerprint.Result fingerprint = fingerprint(token);
        if (!isCurrent(token) || fingerprint == null) {
            clearTask(token);
            return;
        }
        try {
            merge(merged, parseCandidatesInternal(
                    requestJson(token, exactUrl(type, id, fingerprint)), languages,
                    MatchConfidence.EXACT, fingerprint.filename));
        } catch (IOException | JSONException | RuntimeException ignored) {
            clearTask(token);
            return;
        }

        if (!isCurrent(token)) return;
        // Re-register refined ranks. Stable IDs prevent duplicate tracks in PlayerActivity.
        buildConfigurations(sortAndLimit(new ArrayList<>(merged.values())));
        clearTask(token);
    }

    @Nullable
    private OpenSubtitlesMediaFingerprint.Result fingerprint(long token) {
        try {
            return OpenSubtitlesMediaFingerprint.fromCurrentPlayer(httpClient,
                    new OpenSubtitlesMediaFingerprint.CallObserver() {
                        @Override public void onCallStarted(Call call) {
                            activeCall = call;
                            if (!isCurrent(token)) call.cancel();
                        }
                        @Override public void onCallFinished(Call call) {
                            if (activeCall == call) activeCall = null;
                        }
                    });
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String requestJson(long token, HttpUrl url) throws IOException {
        if (!isCurrent(token)) throw new IOException("cancelled");
        Call call = httpClient.newCall(new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus").build());
        activeCall = call;
        try (Response response = call.execute()) {
            if (!isCurrent(token)) throw new IOException("cancelled");
            if (!response.isSuccessful()) throw new IOException("http_" + response.code());
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                throw new IOException("invalid_body");
            }
            String json = body.string();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw new IOException("response_too_large");
            }
            return json;
        } finally {
            if (activeCall == call) activeCall = null;
        }
    }

    private static HttpUrl genericUrl(String type, String id) {
        return baseUrl().newBuilder().addPathSegment("subtitles")
                .addPathSegment(type).addPathSegment(id + ".json").build();
    }

    static HttpUrl exactUrl(String type, String videoId,
                            OpenSubtitlesMediaFingerprint.Result fingerprint) {
        String extra = "videoID=" + Uri.encode(videoId)
                + "&videoSize=" + fingerprint.size
                + "&filename=" + Uri.encode(fingerprint.filename) + ".json";
        return baseUrl().newBuilder().addPathSegment("subtitles")
                .addPathSegment(type).addPathSegment(fingerprint.hash)
                .addEncodedPathSegment(extra).build();
    }

    private static HttpUrl baseUrl() {
        return new Request.Builder().url(BASE_URL).build().url();
    }

    private static void merge(Map<String, Candidate> target, List<Candidate> incoming) {
        for (Candidate candidate : incoming) {
            Candidate old = target.get(candidate.url);
            if (old == null || candidate.confidence.rank < old.confidence.rank) {
                target.put(candidate.url, candidate);
            }
        }
    }

    void cancel() {
        operationToken++;
        Call call = activeCall;
        activeCall = null;
        if (call != null) call.cancel();
        Future<?> task = activeTask;
        activeTask = null;
        if (task != null) task.cancel(true);
    }

    void release() {
        if (released) return;
        cancel();
        released = true;
        executor.shutdownNow();
    }

    private boolean isCurrent(long token) {
        return !released && token == operationToken && !Thread.currentThread().isInterrupted();
    }

    private void clearTask(long token) {
        if (operationToken == token) activeTask = null;
    }

    static boolean isSupportedContent(@Nullable String type, @Nullable String id) {
        return ("movie".equals(type) || "series".equals(type))
                && id != null && id.matches("tt\\d+(?::\\d+:\\d+)?");
    }

    static boolean isOpenSubtitlesTrack(@Nullable String id) {
        return SubtitleTrackIdentity.isOpenSubtitlesV3(id);
    }

    static boolean hasOpenSubtitlesTrack(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.getMediaTrackGroup().length; i++) {
                if (isOpenSubtitlesTrack(group.getMediaTrackGroup().getFormat(i).id)) return true;
            }
        }
        return false;
    }

    static List<Candidate> parseCandidates(String json, String[] languages) throws JSONException {
        return sortAndLimit(parseCandidatesInternal(
                json, languages, MatchConfidence.UNKNOWN, null));
    }

    private static List<Candidate> parseCandidatesInternal(
            String json, String[] languages, MatchConfidence defaultConfidence,
            @Nullable String mediaFilename) throws JSONException {
        LinkedHashMap<String, Integer> ranks = languageRanks(languages);
        JSONArray array = new JSONObject(json).optJSONArray("subtitles");
        if (ranks.isEmpty() || array == null) return new ArrayList<>();

        List<Candidate> result = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        int count = Math.min(array.length(), MAX_SERVER_RESULTS);
        for (int i = 0; i < count; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String language = normalizeLanguage(item.optString("lang", ""));
            Integer rank = ranks.get(language);
            String url = item.optString("url", "").trim();
            if (rank == null || !isSafeSubtitleUrl(url) || !urls.add(url)) continue;

            String identifier = cleanText(item.optString("id", ""));
            String name = cleanText(item.optString("name", ""));
            String hints = (identifier + " " + name).toLowerCase(Locale.ROOT);
            boolean forced = containsAny(hints,
                    " forced", "forced ", ".forced", "_forced", "-forced",
                    " foreign parts", "signs and songs");
            boolean sdh = containsAny(hints,
                    " sdh", "sdh ", ".sdh", "_sdh", "-sdh",
                    "hearing impaired", "hard of hearing");
            int roleFlags = C.ROLE_FLAG_SUBTITLE | (sdh ? C.ROLE_FLAG_CAPTION : 0);
            int selectionFlags = forced ? C.SELECTION_FLAG_FORCED : 0;
            String format = item.optString("format", "").toLowerCase(Locale.ROOT);
            String mime = format.contains("vtt") || url.toLowerCase(Locale.ROOT).contains(".vtt")
                    ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;
            MatchConfidence confidence = defaultConfidence;
            if (confidence != MatchConfidence.EXACT
                    && isLikelyReleaseMatch(mediaFilename, identifier + " " + name)) {
                confidence = MatchConfidence.LIKELY;
            }
            result.add(new Candidate(url, language, buildLabel(language, identifier, name), mime,
                    roleFlags, selectionFlags, confidence, rank, i));
        }
        return result;
    }

    private static List<Candidate> sortAndLimit(List<Candidate> candidates) {
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.languageRank)
                .thenComparingInt(c -> c.confidence.rank)
                .thenComparingInt(OpenSubtitlesV3Client::typeRank)
                .thenComparingInt(c -> c.sourceOrder));
        Map<String, Integer> counts = new HashMap<>();
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int used = counts.containsKey(candidate.language) ? counts.get(candidate.language) : 0;
            if (used >= limitForRank(candidate.languageRank)) continue;
            selected.add(candidate);
            counts.put(candidate.language, used + 1);
        }
        return selected;
    }

    private static int typeRank(Candidate candidate) {
        if ((candidate.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) return 1;
        if ((candidate.roleFlags & C.ROLE_FLAG_CAPTION) != 0) return 2;
        return 0;
    }

    private static LinkedHashMap<String, Integer> languageRanks(String[] languages) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String language : languages) {
            String normalized = normalizeLanguage(language);
            if (!normalized.isEmpty() && !result.containsKey(normalized)) {
                result.put(normalized, result.size());
            }
        }
        return result;
    }

    private static List<MediaItem.SubtitleConfiguration> buildConfigurations(
            List<Candidate> candidates) {
        List<MediaItem.SubtitleConfiguration> result = new ArrayList<>();
        for (Candidate c : candidates) {
            String id = TRACK_ID_PREFIX + shortHash(c.url);
            SubtitleTrackIdentity.registerOpenSubtitlesMatch(
                    id, c.language, c.selectionFlags, c.roleFlags, c.label, c.confidence.rank);
            result.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.url))
                    .setId(id)
                    .setLanguage(c.language).setLabel(c.label).setMimeType(c.mimeType)
                    .setRoleFlags(c.roleFlags).setSelectionFlags(c.selectionFlags).build());
        }
        return result;
    }

    static String normalizeLanguage(@Nullable String language) {
        if (language == null) return "";
        String value = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        if (dash > 0) value = value.substring(0, dash);
        switch (value) {
            case "cs": case "cze": case "ces": return "ces";
            case "sk": case "slo": case "slk": return "slk";
            case "en": case "eng": return "eng";
            case "de": case "ger": case "deu": return "deu";
            case "fr": case "fre": case "fra": return "fra";
            case "es": case "spa": return "spa";
            case "it": case "ita": return "ita";
            case "pl": case "pol": return "pol";
            case "pt": case "por": return "por";
            case "hu": case "hun": return "hun";
            case "ru": case "rus": return "rus";
            case "uk": case "ukr": return "ukr";
            default: return value.matches("[a-z]{2,3}") ? value : "";
        }
    }

    private static int limitForRank(int rank) {
        return rank <= 1 ? 5 : rank == 2 ? 3 : 2;
    }

    private static boolean isSafeSubtitleUrl(String value) {
        try {
            HttpUrl url = new Request.Builder().url(value).build().url();
            return "http".equals(url.scheme()) || "https".equals(url.scheme());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String buildLabel(String language, String id, String name) {
        StringBuilder label = new StringBuilder("OpenSubtitles v3 · ")
                .append(language.toUpperCase(Locale.ROOT));
        String detail = !name.isEmpty() ? name : id;
        if (!detail.isEmpty()) label.append(" · ").append(detail);
        if (label.length() > MAX_LABEL_LENGTH) {
            label.setLength(MAX_LABEL_LENGTH - 1);
            label.append('…');
        }
        return label.toString();
    }

    static boolean isLikelyReleaseMatch(@Nullable String media, @Nullable String candidate) {
        Set<String> a = releaseTokens(media);
        Set<String> b = releaseTokens(candidate);
        if (a.size() < 3 || b.isEmpty() || hasConflictingQuality(a, b)) return false;
        int shared = 0;
        int distinctive = 0;
        for (String token : a) {
            if (b.contains(token)) {
                shared++;
                if (isDistinctive(token)) distinctive++;
            }
        }
        return distinctive >= 2 || shared >= Math.max(3, Math.min(6, a.size() / 2));
    }

    private static Set<String> releaseTokens(@Nullable String value) {
        if (value == null) return Collections.emptySet();
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("web-dl", "webdl").replace("web dl", "webdl")
                .replace("blu-ray", "bluray").replace("blu ray", "bluray")
                .replaceAll("\\.(mkv|mp4|avi|mov|m4v|srt|vtt)$", "")
                .replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3 && !isNoise(token)) result.add(token);
        }
        return result;
    }

    private static boolean isNoise(String token) {
        return "the".equals(token) || "and".equals(token) || "with".equals(token)
                || "subtitle".equals(token) || "subtitles".equals(token)
                || "english".equals(token) || "czech".equals(token);
    }

    private static boolean isDistinctive(String token) {
        return token.matches("(2160p|1080p|720p|480p|bluray|brrip|remux|webdl|webrip|hdtv|"
                + "dvdrip|uhd|x264|x265|h264|h265|hevc|av1|hdr|hdr10|dolby|vision|atmos|"
                + "proper|repack|extended|criterion)");
    }

    private static boolean hasConflictingQuality(Set<String> a, Set<String> b) {
        String ar = first(a, "2160p", "1080p", "720p", "480p");
        String br = first(b, "2160p", "1080p", "720p", "480p");
        if (ar != null && br != null && !ar.equals(br)) return true;
        String as = first(a, "remux", "bluray", "webdl", "webrip", "hdtv", "dvdrip");
        String bs = first(b, "remux", "bluray", "webdl", "webrip", "hdtv", "dvdrip");
        return as != null && bs != null && !as.equals(bs);
    }

    @Nullable
    private static String first(Set<String> tokens, String... values) {
        for (String value : values) if (tokens.contains(value)) return value;
        return null;
    }

    private static String currentFilename() {
        if (PlayerActivity.player == null
                || PlayerActivity.player.getCurrentMediaItem() == null
                || PlayerActivity.player.getCurrentMediaItem().localConfiguration == null) return "";
        String segment = PlayerActivity.player.getCurrentMediaItem()
                .localConfiguration.uri.getLastPathSegment();
        return segment == null ? "" : Uri.decode(segment);
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... markers) {
        String padded = " " + value + " ";
        for (String marker : markers) if (padded.contains(marker)) return true;
        return false;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
