package com.brouken.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
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
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Loads OpenSubtitles using the original non-blocking request path. File hashing runs only as an
 * independent background refinement and can never delay or cancel the initial subtitle list.
 */
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
        void onRefined(List<MediaItem.SubtitleConfiguration> subtitles);
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

        Candidate(String url,
                  String language,
                  String label,
                  String mimeType,
                  int roleFlags,
                  int selectionFlags,
                  MatchConfidence confidence,
                  int languageRank,
                  int sourceOrder) {
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
    private final ExecutorService refinementExecutor = Executors.newSingleThreadExecutor();
    @Nullable private volatile Call activeInitialCall;
    @Nullable private volatile Call activeRefinementCall;
    @Nullable private volatile Future<?> activeRefinementTask;
    @Nullable private Listener currentListener;
    @Nullable private String currentType;
    @Nullable private String currentId;
    private String[] currentLanguages = new String[0];
    private List<Candidate> currentGenericCandidates = new ArrayList<>();
    @Nullable private List<Candidate> pendingExactCandidates;
    private boolean genericFinished;
    private boolean genericSucceeded;
    private boolean refinementStarted;
    private boolean exactDelivered;
    private volatile long operationToken;
    private volatile boolean released;

    OpenSubtitlesV3Client(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    synchronized void fetch(String type,
                            String id,
                            String[] preferredLanguages,
                            @Nullable String mediaFilename,
                            @Nullable Tracks currentTracks,
                            Listener listener) {
        if (released) {
            return;
        }
        cancel();
        SubtitleTrackIdentity.resetOpenSubtitlesMatches();
        final long token = operationToken;
        final String[] languages = requestedLanguages(preferredLanguages, currentTracks);
        if (!isSupportedContent(type, id) || languages.length == 0) {
            listener.onLoaded(new ArrayList<>());
            return;
        }

        currentListener = listener;
        currentType = type;
        currentId = id;
        currentLanguages = languages;
        currentGenericCandidates = new ArrayList<>();
        pendingExactCandidates = null;
        genericFinished = false;
        genericSucceeded = false;
        refinementStarted = false;
        exactDelivered = false;
        final String filename = mediaFilename == null ? "" : mediaFilename;
        Request request = new Request.Builder()
                .url(genericUrl(type, id))
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus")
                .build();
        Call call = httpClient.newCall(request);
        activeInitialCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call failedCall, @NonNull IOException error) {
                if (isCurrent(token) && !failedCall.isCanceled()) {
                    activeInitialCall = null;
                    reportGenericFailure(token, listener, "network");
                }
            }

            @Override
            public void onResponse(@NonNull Call responseCall, @NonNull Response response) {
                try (Response closeable = response) {
                    if (!isCurrent(token)) {
                        return;
                    }
                    if (!closeable.isSuccessful()) {
                        activeInitialCall = null;
                        reportGenericFailure(
                                token, listener, "http_" + closeable.code());
                        return;
                    }
                    ResponseBody body = closeable.body();
                    if (body == null) {
                        activeInitialCall = null;
                        reportGenericFailure(token, listener, "invalid_body");
                        return;
                    }
                    String json = BoundedResponseBody.readUtf8(body, MAX_RESPONSE_BYTES);
                    List<Candidate> genericCandidates = parseCandidatesInternal(
                            json, languages, MatchConfidence.UNKNOWN, filename);
                    List<MediaItem.SubtitleConfiguration> configurations =
                            buildConfigurationsIfCurrent(
                                    token,
                                    sortAndLimit(new ArrayList<>(genericCandidates)));
                    if (configurations == null) {
                        return;
                    }
                    activeInitialCall = null;
                    deliverGeneric(token, genericCandidates, configurations, listener);
                } catch (BoundedResponseBody.ResponseTooLargeException error) {
                    if (isCurrent(token)) {
                        activeInitialCall = null;
                        reportGenericFailure(token, listener, "response_too_large");
                    }
                } catch (IOException | JSONException | RuntimeException error) {
                    if (isCurrent(token)) {
                        activeInitialCall = null;
                        reportGenericFailure(token, listener, "invalid_response");
                    }
                }
            }
        });
    }

    /**
     * Starts optional hash refinement after Player reaches READY. The MediaItem and track snapshot
     * must be captured on Player's application thread; background work never calls Player methods.
     */
    synchronized void startRefinement(MediaItem mediaItem, @Nullable Tracks currentTracks) {
        if (released || refinementStarted || currentListener == null
                || currentType == null || currentId == null
                || mediaItem.localConfiguration == null) {
            return;
        }
        refinementStarted = true;
        final long token = operationToken;
        final String type = currentType;
        final String id = currentId;
        final Listener listener = currentListener;
        final String[] languages = requestedLanguages(currentLanguages, currentTracks);
        activeRefinementTask = refinementExecutor.submit(() -> {
            OpenSubtitlesMediaFingerprint.Result fingerprint = fingerprint(token, mediaItem);
            if (!isCurrent(token) || fingerprint == null) {
                clearRefinementTask(token);
                return;
            }

            try {
                String json = requestRefinementJson(token, exactUrl(type, id, fingerprint));
                List<Candidate> exactCandidates = parseCandidatesInternal(
                        json, languages, MatchConfidence.EXACT, fingerprint.filename);
                if (exactCandidates.isEmpty()) {
                    return;
                }
                deliverExact(token, exactCandidates, listener);
            } catch (IOException | JSONException | RuntimeException ignored) {
                // Generic OpenSubtitles results, when available, remain usable.
            } finally {
                clearRefinementTask(token);
            }
        });
    }

    @Nullable
    private OpenSubtitlesMediaFingerprint.Result fingerprint(long token, MediaItem mediaItem) {
        try {
            return OpenSubtitlesMediaFingerprint.fromMediaItem(httpClient, mediaItem,
                    new OpenSubtitlesMediaFingerprint.CallObserver() {
                        @Override
                        public void onCallStarted(Call call) {
                            activeRefinementCall = call;
                            if (!isCurrent(token)) {
                                call.cancel();
                            }
                        }

                        @Override
                        public void onCallFinished(Call call) {
                            if (activeRefinementCall == call) {
                                activeRefinementCall = null;
                            }
                        }
                    });
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String requestRefinementJson(long token, HttpUrl url) throws IOException {
        if (!isCurrent(token)) {
            throw new IOException("cancelled");
        }
        Call call = httpClient.newCall(new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus")
                .build());
        activeRefinementCall = call;
        try (Response response = call.execute()) {
            if (!isCurrent(token)) {
                throw new IOException("cancelled");
            }
            if (!response.isSuccessful()) {
                throw new IOException("http_" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("invalid_body");
            }
            return BoundedResponseBody.readUtf8(body, MAX_RESPONSE_BYTES);
        } finally {
            if (activeRefinementCall == call) {
                activeRefinementCall = null;
            }
        }
    }

    private static String[] requestedLanguages(String[] preferred, @Nullable Tracks tracks) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String language : preferred) {
            addLanguage(result, language);
        }
        if (tracks != null) {
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) {
                    continue;
                }
                for (int index = 0; index < group.getMediaTrackGroup().length; index++) {
                    addLanguage(result, group.getMediaTrackGroup().getFormat(index).language);
                }
            }
        }
        return result.toArray(new String[0]);
    }

    private void deliverGeneric(
            long token,
            List<Candidate> candidates,
            List<MediaItem.SubtitleConfiguration> configurations,
            Listener listener) {
        List<Candidate> pendingExact;
        synchronized (this) {
            if (!isCurrent(token) || exactDelivered) {
                return;
            }
            currentGenericCandidates = new ArrayList<>(candidates);
            genericFinished = true;
            genericSucceeded = true;
            pendingExact = pendingExactCandidates;
            pendingExactCandidates = null;
        }
        if (pendingExact != null) {
            deliverExact(token, pendingExact, listener);
        } else if (isCurrent(token)) {
            listener.onLoaded(configurations);
        }
    }

    private void reportGenericFailure(
            long token, Listener listener, String reason) {
        List<Candidate> pendingExact;
        synchronized (this) {
            if (!isCurrent(token) || exactDelivered) {
                return;
            }
            genericFinished = true;
            genericSucceeded = false;
            pendingExact = pendingExactCandidates;
            pendingExactCandidates = null;
        }
        if (pendingExact != null) {
            deliverExact(token, pendingExact, listener);
        } else if (isCurrent(token)) {
            listener.onFailure(reason);
        }
    }

    private void deliverExact(long token,
                              List<Candidate> exactCandidates,
                              Listener listener) {
        List<MediaItem.SubtitleConfiguration> configurations;
        synchronized (this) {
            if (!isCurrent(token) || exactDelivered) {
                return;
            }
            if (!genericFinished) {
                pendingExactCandidates = new ArrayList<>(exactCandidates);
                return;
            }
            List<Candidate> merged = genericSucceeded
                    ? mergeRefinedCandidates(currentGenericCandidates, exactCandidates)
                    : sortAndLimit(new ArrayList<>(exactCandidates));
            configurations = buildConfigurations(merged);
            exactDelivered = true;
        }
        if (isCurrent(token)) {
            listener.onRefined(configurations);
        }
    }

    private static void addLanguage(Set<String> target, @Nullable String language) {
        String normalized = normalizeLanguage(language);
        if (!normalized.isEmpty() && !"und".equals(normalized)) {
            target.add(normalized);
        }
    }

    private static HttpUrl genericUrl(String type, String id) {
        return baseUrl().newBuilder()
                .addPathSegment("subtitles")
                .addPathSegment(type)
                .addPathSegment(id + ".json")
                .build();
    }

    static HttpUrl exactUrl(String type,
                            String videoId,
                            OpenSubtitlesMediaFingerprint.Result fingerprint) {
        String extra = "videoID=" + Uri.encode(videoId)
                + "&videoSize=" + fingerprint.size
                + "&filename=" + Uri.encode(fingerprint.filename) + ".json";
        return baseUrl().newBuilder()
                .addPathSegment("subtitles")
                .addPathSegment(type)
                .addPathSegment(fingerprint.hash)
                .addEncodedPathSegment(extra)
                .build();
    }

    private static HttpUrl baseUrl() {
        return new Request.Builder().url(BASE_URL).build().url();
    }

    private static void merge(Map<String, Candidate> target, List<Candidate> incoming) {
        for (Candidate candidate : incoming) {
            Candidate previous = target.get(candidate.url);
            if (previous == null || candidate.confidence.rank < previous.confidence.rank) {
                target.put(candidate.url, candidate);
            }
        }
    }

    static List<Candidate> mergeRefinedCandidates(List<Candidate> genericCandidates,
                                                   List<Candidate> exactCandidates) {
        LinkedHashMap<String, Candidate> merged = new LinkedHashMap<>();
        merge(merged, genericCandidates);
        merge(merged, exactCandidates);
        return sortAndLimit(new ArrayList<>(merged.values()));
    }

    synchronized void cancel() {
        operationToken++;
        currentListener = null;
        currentType = null;
        currentId = null;
        currentLanguages = new String[0];
        currentGenericCandidates = new ArrayList<>();
        pendingExactCandidates = null;
        genericFinished = false;
        genericSucceeded = false;
        refinementStarted = false;
        exactDelivered = false;

        Call initial = activeInitialCall;
        activeInitialCall = null;
        if (initial != null) {
            initial.cancel();
        }

        Call refinement = activeRefinementCall;
        activeRefinementCall = null;
        if (refinement != null) {
            refinement.cancel();
        }

        Future<?> task = activeRefinementTask;
        activeRefinementTask = null;
        if (task != null) {
            task.cancel(true);
        }
    }

    synchronized void release() {
        if (released) {
            return;
        }
        cancel();
        released = true;
        refinementExecutor.shutdownNow();
    }

    private boolean isCurrent(long token) {
        return !released && token == operationToken;
    }

    @Nullable
    private synchronized List<MediaItem.SubtitleConfiguration> buildConfigurationsIfCurrent(
            long token,
            List<Candidate> candidates) {
        return isCurrent(token) ? buildConfigurations(candidates) : null;
    }

    private synchronized void clearRefinementTask(long token) {
        if (operationToken == token) {
            activeRefinementTask = null;
        }
    }

    static boolean isSupportedContent(@Nullable String type, @Nullable String id) {
        return ("movie".equals(type) || "series".equals(type))
                && id != null
                && id.matches("tt\\d+(?::\\d+:\\d+)?");
    }

    static boolean isOpenSubtitlesTrack(@Nullable String id) {
        return SubtitleTrackIdentity.isOpenSubtitlesV3(id);
    }

    static boolean hasOpenSubtitlesTrack(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int index = 0; index < group.getMediaTrackGroup().length; index++) {
                Format format = group.getMediaTrackGroup().getFormat(index);
                if (SubtitleTrackIdentity.isOpenSubtitlesV3(format.id, format.label)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<Candidate> parseCandidates(String json, String[] languages)
            throws JSONException {
        return sortAndLimit(parseCandidatesInternal(
                json, languages, MatchConfidence.UNKNOWN, null));
    }

    private static List<Candidate> parseCandidatesInternal(
            String json,
            String[] languages,
            MatchConfidence defaultConfidence,
            @Nullable String mediaFilename) throws JSONException {
        LinkedHashMap<String, Integer> languageRanks = languageRanks(languages);
        JSONArray subtitles = new JSONObject(json).optJSONArray("subtitles");
        if (languageRanks.isEmpty() || subtitles == null) {
            return new ArrayList<>();
        }

        List<Candidate> result = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        int length = Math.min(subtitles.length(), MAX_SERVER_RESULTS);
        for (int index = 0; index < length; index++) {
            JSONObject item = subtitles.optJSONObject(index);
            if (item == null) {
                continue;
            }

            String language = normalizeLanguage(item.optString("lang", ""));
            Integer languageRank = languageRanks.get(language);
            String url = item.optString("url", "").trim();
            if (languageRank == null || !isSafeSubtitleUrl(url) || !seenUrls.add(url)) {
                continue;
            }

            String identifier = cleanText(item.optString("id", ""));
            String name = cleanText(item.optString("name", ""));
            String flagsText = (identifier + " " + name).toLowerCase(Locale.ROOT);
            boolean forced = containsAny(flagsText,
                    " forced", "forced ", ".forced", "_forced", "-forced",
                    " foreign parts", "signs and songs");
            boolean sdh = containsAny(flagsText,
                    " sdh", "sdh ", ".sdh", "_sdh", "-sdh",
                    "hearing impaired", "hard of hearing");
            int roleFlags = C.ROLE_FLAG_SUBTITLE | (sdh ? C.ROLE_FLAG_CAPTION : 0);
            int selectionFlags = forced ? C.SELECTION_FLAG_FORCED : 0;
            String format = item.optString("format", "").toLowerCase(Locale.ROOT);
            String lowerUrl = url.toLowerCase(Locale.ROOT);
            String mimeType = format.contains("vtt") || lowerUrl.contains(".vtt")
                    ? MimeTypes.TEXT_VTT
                    : MimeTypes.APPLICATION_SUBRIP;

            MatchConfidence confidence = defaultConfidence;
            if (confidence != MatchConfidence.EXACT
                    && isLikelyReleaseMatch(mediaFilename, identifier + " " + name)) {
                confidence = MatchConfidence.LIKELY;
            }

            result.add(new Candidate(
                    url,
                    language,
                    buildLabel(language, identifier, name),
                    mimeType,
                    roleFlags,
                    selectionFlags,
                    confidence,
                    languageRank,
                    index));
        }
        return result;
    }

    private static List<Candidate> sortAndLimit(List<Candidate> candidates) {
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate first, Candidate second) {
                int result = Integer.compare(first.languageRank, second.languageRank);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(first.confidence.rank, second.confidence.rank);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(typeRank(first), typeRank(second));
                if (result != 0) {
                    return result;
                }
                return Integer.compare(first.sourceOrder, second.sourceOrder);
            }
        });

        Map<String, Integer> languageCounts = new HashMap<>();
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int used = languageCounts.containsKey(candidate.language)
                    ? languageCounts.get(candidate.language)
                    : 0;
            if (used >= limitForRank(candidate.languageRank)) {
                continue;
            }
            selected.add(candidate);
            languageCounts.put(candidate.language, used + 1);
        }
        return selected;
    }

    private static int typeRank(Candidate candidate) {
        if ((candidate.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            return 1;
        }
        if ((candidate.roleFlags & C.ROLE_FLAG_CAPTION) != 0) {
            return 2;
        }
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
        for (Candidate candidate : candidates) {
            String id = TRACK_ID_PREFIX + shortHash(candidate.url);
            SubtitleTrackIdentity.registerOpenSubtitlesMatch(
                    id,
                    candidate.language,
                    candidate.selectionFlags,
                    candidate.roleFlags,
                    candidate.label,
                    candidate.confidence.rank);
            result.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(candidate.url))
                    .setId(id)
                    .setLanguage(candidate.language)
                    .setLabel(candidate.label)
                    .setMimeType(candidate.mimeType)
                    .setRoleFlags(candidate.roleFlags)
                    .setSelectionFlags(candidate.selectionFlags)
                    .build());
        }
        return result;
    }

    static String normalizeLanguage(@Nullable String language) {
        if (language == null) {
            return "";
        }
        String value = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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

    static boolean isLikelyReleaseMatch(@Nullable String media, @Nullable String candidate) {
        Set<String> mediaTokens = releaseTokens(media);
        Set<String> candidateTokens = releaseTokens(candidate);
        if (mediaTokens.size() < 3
                || candidateTokens.isEmpty()
                || hasConflictingQuality(mediaTokens, candidateTokens)) {
            return false;
        }
        int shared = 0;
        int distinctive = 0;
        for (String token : mediaTokens) {
            if (candidateTokens.contains(token)) {
                shared++;
                if (isDistinctive(token)) {
                    distinctive++;
                }
            }
        }
        return distinctive >= 2
                || shared >= Math.max(3, Math.min(6, mediaTokens.size() / 2));
    }

    private static Set<String> releaseTokens(@Nullable String value) {
        if (value == null) {
            return Collections.emptySet();
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("web-dl", "webdl")
                .replace("web dl", "webdl")
                .replace("blu-ray", "bluray")
                .replace("blu ray", "bluray")
                .replaceAll("\\.(mkv|mp4|avi|mov|m4v|srt|vtt)$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3 && !isNoise(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static boolean isNoise(String token) {
        return "the".equals(token)
                || "and".equals(token)
                || "with".equals(token)
                || "subtitle".equals(token)
                || "subtitles".equals(token)
                || "english".equals(token)
                || "czech".equals(token);
    }

    private static boolean isDistinctive(String token) {
        return token.matches("(2160p|1080p|720p|480p|bluray|brrip|remux|webdl|webrip|hdtv|"
                + "dvdrip|uhd|x264|x265|h264|h265|hevc|av1|hdr|hdr10|dolby|vision|atmos|"
                + "proper|repack|extended|criterion)");
    }

    private static boolean hasConflictingQuality(Set<String> first, Set<String> second) {
        String firstResolution = first(first, "2160p", "1080p", "720p", "480p");
        String secondResolution = first(second, "2160p", "1080p", "720p", "480p");
        if (firstResolution != null
                && secondResolution != null
                && !firstResolution.equals(secondResolution)) {
            return true;
        }
        String firstSource = first(first,
                "remux", "bluray", "webdl", "webrip", "hdtv", "dvdrip");
        String secondSource = first(second,
                "remux", "bluray", "webdl", "webrip", "hdtv", "dvdrip");
        return firstSource != null
                && secondSource != null
                && !firstSource.equals(secondSource);
    }

    @Nullable
    private static String first(Set<String> tokens, String... values) {
        for (String value : values) {
            if (tokens.contains(value)) {
                return value;
            }
        }
        return null;
    }

    private static String cleanText(String value) {
        return value == null
                ? ""
                : value.replace('\r', ' ')
                        .replace('\n', ' ')
                        .replaceAll("\\s+", " ")
                        .trim();
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
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
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
