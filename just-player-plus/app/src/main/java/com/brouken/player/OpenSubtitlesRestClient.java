package com.brouken.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;

import com.brouken.player.aisubtitles.SubtitleTrackIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Direct OpenSubtitles REST API integration for exact hash and conservative release matches. */
final class OpenSubtitlesRestClient {
    static final String TRACK_ID_PREFIX = SmartSubtitleSelector.EXTERNAL_ID_PREFIX
            + "opensubtitles-rest:";
    private static final String API_BASE = "https://api.opensubtitles.com/api/v1/";
    private static final String USER_AGENT = "JustPlayerPlus v1.0";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESULTS = 500;
    private static final int MIN_SEARCH_PAGES = 3;
    private static final int MAX_SEARCH_PAGES = 10;
    private static final int STRONG_RELEASE_SCORE = 70;

    interface Listener {
        void onEvent(String state, String detail);
        void onResolved(Result result);
        void onFailure(String reason);
    }

    static final class Result {
        final int exactResults;
        final int likelyResults;
        final int verifiedExact;
        final int verifiedLikely;
        final int verifiedExisting;
        @Nullable final MediaItem.SubtitleConfiguration directSubtitle;

        Result(int exactResults,
               int likelyResults,
               int verifiedExact,
               int verifiedLikely,
               @Nullable MediaItem.SubtitleConfiguration directSubtitle) {
            this.exactResults = exactResults;
            this.likelyResults = likelyResults;
            this.verifiedExact = verifiedExact;
            this.verifiedLikely = verifiedLikely;
            this.verifiedExisting = verifiedExact + verifiedLikely;
            this.directSubtitle = directSubtitle;
        }
    }

    static final class TestResult {
        final boolean success;
        final boolean accountVerified;
        final String reason;

        TestResult(boolean success, boolean accountVerified, String reason) {
            this.success = success;
            this.accountVerified = accountVerified;
            this.reason = reason;
        }
    }

    static final class Candidate {
        final String fileId;
        final Set<String> identifiers;
        final String language;
        final String release;
        final String filename;
        final int languageRank;
        final boolean hearingImpaired;
        final boolean forced;
        final boolean trusted;
        final int downloads;
        final int releaseScore;

        Candidate(String fileId,
                  Set<String> identifiers,
                  String language,
                  String release,
                  String filename,
                  int languageRank,
                  boolean hearingImpaired,
                  boolean forced,
                  boolean trusted,
                  int downloads,
                  int releaseScore) {
            this.fileId = fileId;
            this.identifiers = identifiers;
            this.language = language;
            this.release = release;
            this.filename = filename;
            this.languageRank = languageRank;
            this.hearingImpaired = hearingImpaired;
            this.forced = forced;
            this.trusted = trusted;
            this.downloads = downloads;
            this.releaseScore = releaseScore;
        }
    }

    private static final class SearchPageStats {
        final int rawResults;
        final int preferredLanguageResults;
        final int fileResults;
        final int totalPages;
        final int totalCount;
        final int bestScore;
        final String bestReason;
        final String bestCandidate;

        SearchPageStats(int rawResults,
                        int preferredLanguageResults,
                        int fileResults,
                        int totalPages,
                        int totalCount,
                        int bestScore,
                        String bestReason,
                        String bestCandidate) {
            this.rawResults = rawResults;
            this.preferredLanguageResults = preferredLanguageResults;
            this.fileResults = fileResults;
            this.totalPages = totalPages;
            this.totalCount = totalCount;
            this.bestScore = bestScore;
            this.bestReason = bestReason;
            this.bestCandidate = bestCandidate;
        }
    }

    private final Context context;
    private final OkHttpClient mediaHttpClient;
    private final OkHttpClient apiHttpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Call> activeCalls = Collections.synchronizedSet(new HashSet<>());
    @Nullable private Future<?> activeTask;
    private volatile long operationToken;
    private volatile boolean released;

    OpenSubtitlesRestClient(Context context, OkHttpClient httpClient) {
        this.context = context.getApplicationContext();
        mediaHttpClient = httpClient;
        apiHttpClient = apiClient(httpClient);
        deleteAbandonedCacheFiles();
    }

    synchronized void resolve(MediaItem mediaItem,
                              String contentType,
                              String contentId,
                              @Nullable String mediaFilename,
                              OpenSubtitlesCredentialsStore.Credentials credentials,
                              String[] preferredLanguages,
                              List<MediaItem.SubtitleConfiguration> existing,
                              boolean allowDirectSubtitle,
                              Listener listener) {
        if (released) {
            return;
        }
        cancel();
        final long token = operationToken;
        final List<MediaItem.SubtitleConfiguration> existingSnapshot =
                new ArrayList<>(existing);
        final String[] languageSnapshot = preferredLanguages.clone();
        activeTask = executor.submit(() -> runResolve(
                token,
                mediaItem,
                contentType,
                contentId,
                mediaFilename,
                credentials,
                languageSnapshot,
                existingSnapshot,
                allowDirectSubtitle,
                listener));
    }

    private void runResolve(long token,
                            MediaItem mediaItem,
                            String contentType,
                            String contentId,
                            @Nullable String mediaFilename,
                            OpenSubtitlesCredentialsStore.Credentials credentials,
                            String[] preferredLanguages,
                            List<MediaItem.SubtitleConfiguration> existing,
                            boolean allowDirectSubtitle,
                            Listener listener) {
        if (!isCurrent(token) || credentials == null || !credentials.isValid()) {
            return;
        }
        try {
            OpenSubtitlesMediaFingerprint.Result fingerprint =
                    OpenSubtitlesMediaFingerprint.fromMediaItem(
                            mediaHttpClient,
                            mediaItem,
                            new OpenSubtitlesMediaFingerprint.CallObserver() {
                                @Override
                                public void onCallStarted(Call call) {
                                    activeCalls.add(call);
                                }

                                @Override
                                public void onCallFinished(Call call) {
                                    activeCalls.remove(call);
                                }

                                @Override
                                public void onFingerprintEvent(String state, String detail) {
                                    if (isCurrent(token)) {
                                        listener.onEvent("fingerprint_" + state, detail);
                                    }
                                }
                            });
            if (!isCurrent(token)) {
                return;
            }
            if (fingerprint == null) {
                listener.onFailure("fingerprint_unavailable");
                return;
            }

            listener.onEvent(
                    "search_started",
                    "size=" + fingerprint.size
                            + " languages=" + preferredLanguages.length
                            + " filename=" + filenameDiagnostic(mediaFilename));
            List<Candidate> exactCandidates = new ArrayList<>();
            List<Candidate> likelyCandidates = new ArrayList<>();
            int rawResults = 0;
            int preferredLanguageResults = 0;
            int fileResults = 0;
            int totalCount = 0;
            int bestScore = 0;
            String bestReason = releaseQuery(mediaFilename).isEmpty()
                    ? "filename_unavailable" : "no_candidates";
            String bestCandidate = "";
            int totalPages = 1;
            for (int page = 1;
                 page <= Math.min(totalPages, MAX_SEARCH_PAGES);
                 page++) {
                String searchJson = executeJson(
                        token,
                        searchRequest(
                                credentials.apiKey,
                                fingerprint.hash,
                                fingerprint.size,
                                preferredLanguages,
                                contentType,
                                contentId,
                                mediaFilename,
                                page));
                SearchPageStats stats = analyzeSearchPage(
                        searchJson, preferredLanguages, mediaFilename);
                totalPages = Math.max(1, stats.totalPages);
                totalCount = Math.max(totalCount, stats.totalCount);
                rawResults += stats.rawResults;
                preferredLanguageResults += stats.preferredLanguageResults;
                fileResults += stats.fileResults;
                if (stats.bestScore > bestScore
                        || (bestCandidate.isEmpty() && !stats.bestCandidate.isEmpty())) {
                    bestScore = stats.bestScore;
                    bestReason = stats.bestReason;
                    bestCandidate = stats.bestCandidate;
                }
                List<Candidate> pageExact = parseExactCandidates(
                        searchJson, preferredLanguages);
                List<Candidate> pageLikely = parseLikelyCandidates(
                        searchJson, preferredLanguages, mediaFilename);
                exactCandidates.addAll(pageExact);
                likelyCandidates.addAll(pageLikely);
                listener.onEvent(
                        "search_page",
                        "page=" + page
                                + " raw=" + stats.rawResults
                                + " language=" + stats.preferredLanguageResults
                                + " exact=" + pageExact.size()
                                + " likely=" + pageLikely.size()
                                + " best=" + stats.bestScore
                                + " reason=" + stats.bestReason);
                if (!isCurrent(token)) {
                    return;
                }
                if (stats.rawResults == 0
                        || !shouldSearchNextPage(
                                page,
                                totalPages,
                                !exactCandidates.isEmpty(),
                                bestScore)) {
                    break;
                }
            }
            exactCandidates = deduplicateCandidates(exactCandidates);
            likelyCandidates = deduplicateCandidates(likelyCandidates);
            if (!isCurrent(token)) {
                return;
            }
            listener.onEvent(
                    "search_complete",
                    "raw=" + rawResults
                            + "/" + totalCount
                            + " language=" + preferredLanguageResults
                            + " files=" + fileResults
                            + " exact=" + exactCandidates.size()
                            + " likely=" + likelyCandidates.size());
            listener.onEvent(
                    "search_best_release",
                    "score=" + bestScore
                            + " reason=" + bestReason
                            + " candidate=" + bestCandidate);
            if (exactCandidates.isEmpty() && likelyCandidates.isEmpty()) {
                listener.onResolved(new Result(0, 0, 0, 0, null));
                return;
            }

            Set<Candidate> matchedCandidates = new HashSet<>();
            int verifiedExact = verifyExisting(
                    existing, exactCandidates, matchedCandidates);
            int verifiedLikely = verifyLikelyExisting(existing, likelyCandidates);
            listener.onEvent(
                    "mapping_complete",
                    "tracks=" + existing.size()
                            + " exact=" + verifiedExact + "/" + exactCandidates.size()
                            + " likely=" + verifiedLikely + "/" + likelyCandidates.size());
            MediaItem.SubtitleConfiguration direct = null;
            if (allowDirectSubtitle
                    && !exactCandidates.isEmpty()
                    && !matchedCandidates.contains(exactCandidates.get(0))) {
                Candidate best = exactCandidates.get(0);
                if (credentials.hasAccount()) {
                    String tokenValue = login(token, credentials);
                    if (!isCurrent(token)) {
                        return;
                    }
                    String linkJson = executeJson(
                            token,
                            downloadLinkRequest(
                                    credentials.apiKey, tokenValue, best.fileId));
                    JSONObject linkResponse = new JSONObject(linkJson);
                    String link = linkResponse.optString("link", "").trim();
                    String serverFilename = linkResponse.optString(
                            "file_name", best.filename);
                    File cached = downloadSubtitle(
                            token, link, serverFilename);
                    if (!isCurrent(token)) {
                        deleteQuietly(cached);
                        return;
                    }
                    direct = buildConfiguration(best, cached);
                    listener.onEvent(
                            "direct_ready",
                            "language=" + best.language + " bytes=" + cached.length());
                } else {
                    listener.onEvent(
                            "direct_skipped",
                            "reason=account_not_configured");
                }
            }
            if (isCurrent(token)) {
                listener.onResolved(new Result(
                        exactCandidates.size(),
                        likelyCandidates.size(),
                        verifiedExact,
                        verifiedLikely,
                        direct));
            }
        } catch (HttpFailure error) {
            if (isCurrent(token)) {
                listener.onFailure(error.reason);
            }
        } catch (IOException | JSONException | RuntimeException error) {
            if (isCurrent(token)) {
                listener.onFailure("invalid_response");
            }
        }
    }

    static boolean shouldSearchNextPage(int currentPage,
                                        int totalPages,
                                        boolean exactMatchFound,
                                        int bestReleaseScore) {
        int cappedPages = Math.min(Math.max(1, totalPages), MAX_SEARCH_PAGES);
        if (currentPage >= cappedPages) {
            return false;
        }
        if (currentPage < MIN_SEARCH_PAGES) {
            return true;
        }
        return !exactMatchFound && bestReleaseScore < STRONG_RELEASE_SCORE;
    }

    static Request searchRequest(String apiKey,
                                 String hash,
                                 long size,
                                 String[] languages,
                                 String contentType,
                                 String contentId,
                                 @Nullable String mediaFilename) {
        return searchRequest(
                apiKey, hash, size, languages, contentType, contentId, mediaFilename, 1);
    }

    static Request searchRequest(String apiKey,
                                 String hash,
                                 long size,
                                 String[] languages,
                                 String contentType,
                                 String contentId,
                                 @Nullable String mediaFilename,
                                 int page) {
        HttpUrl.Builder url = apiUrl("subtitles").newBuilder();
        StremioEpisodeId episode = "series".equals(contentType)
                ? StremioEpisodeId.parse(contentId) : null;
        String imdbId = imdbDigits(episode == null ? contentId : episode.metaId);
        if (episode != null) {
            url.addQueryParameter("episode_number", Integer.toString(episode.episode));
        } else if (!imdbId.isEmpty()) {
            url.addQueryParameter("imdb_id", imdbId);
        }
        String apiLanguages = apiLanguages(languages);
        if (!apiLanguages.isEmpty()) {
            url.addQueryParameter("languages", apiLanguages);
        }
        // OpenSubtitles canonicalizes query parameters alphabetically and answers a differently
        // ordered URL with HTTP 301. Build the canonical form so secret-bearing API requests
        // never need to follow redirects. Do not send moviehash_match=include: include is the
        // server default and OpenSubtitles redirects that explicit value to a URL without it.
        // REST v1 identifies an OpenSubtitles hash without moviebytesize. Keep filename matching
        // local: combining a full release-name query with the stable IMDb/episode filters can
        // hide otherwise valid releases that should be scored by the client.
        url.addQueryParameter("moviehash", hash);
        if (page > 1) {
            url.addQueryParameter("page", Integer.toString(page));
        }
        if (episode != null && !imdbId.isEmpty()) {
            url.addQueryParameter("parent_imdb_id", imdbId);
        }
        if (episode != null) {
            url.addQueryParameter("season_number", Integer.toString(episode.season))
                    .addQueryParameter("type", "episode");
        } else {
            url.addQueryParameter("type", "movie");
        }
        return apiRequest(url.build(), apiKey).get().build();
    }

    static Request credentialsTestRequest(String apiKey) {
        HttpUrl url = apiUrl("subtitles").newBuilder()
                .addQueryParameter("languages", "en")
                .addEncodedQueryParameter("query", "the+matrix")
                .build();
        return apiRequest(url, apiKey).get().build();
    }

    private Request loginRequest(OpenSubtitlesCredentialsStore.Credentials credentials)
            throws JSONException {
        JSONObject body = new JSONObject()
                .put("username", credentials.username)
                .put("password", credentials.password);
        return apiRequest(apiUrl("login"), credentials.apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    private Request downloadLinkRequest(String apiKey, String bearer, String fileId)
            throws JSONException {
        JSONObject body = new JSONObject()
                .put("file_id", Long.parseLong(fileId))
                .put("sub_format", "srt");
        return apiRequest(apiUrl("download"), apiKey)
                .header("Authorization", "Bearer " + bearer)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    private String login(long operation,
                         OpenSubtitlesCredentialsStore.Credentials credentials)
            throws IOException, JSONException, HttpFailure {
        String json = executeJson(operation, loginRequest(credentials));
        String token = new JSONObject(json).optString("token", "").trim();
        if (token.isEmpty()) {
            throw new HttpFailure("login_invalid_response");
        }
        return token;
    }

    private String executeJson(long operation, Request request)
            throws IOException, HttpFailure {
        Call call = apiHttpClient.newCall(request);
        activeCalls.add(call);
        try (Response response = call.execute()) {
            if (!isCurrent(operation)) {
                throw new IOException("cancelled");
            }
            if (!response.isSuccessful()) {
                throw new HttpFailure(httpReason(response.code()));
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new HttpFailure("missing_body");
            }
            try {
                return BoundedResponseBody.readUtf8(body, MAX_JSON_BYTES);
            } catch (BoundedResponseBody.ResponseTooLargeException error) {
                throw new HttpFailure("response_too_large");
            }
        } finally {
            activeCalls.remove(call);
        }
    }

    private File downloadSubtitle(long operation,
                                  String value,
                                  String serverFilename)
            throws IOException, HttpFailure {
        HttpUrl url;
        try {
            url = new Request.Builder().url(value).build().url();
        } catch (IllegalArgumentException error) {
            throw new HttpFailure("invalid_download_link");
        }
        if (!"https".equals(url.scheme())) {
            throw new HttpFailure("insecure_download_link");
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .header("User-Agent", USER_AGENT)
                .build();
        Call call = mediaHttpClient.newCall(request);
        activeCalls.add(call);
        File output = File.createTempFile(
                "opensubtitles-rest-", subtitleSuffix(serverFilename),
                context.getCacheDir());
        boolean success = false;
        try (Response response = call.execute()) {
            if (!isCurrent(operation)) {
                throw new IOException("cancelled");
            }
            if (!response.isSuccessful()) {
                throw new HttpFailure(httpReason(response.code()));
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new HttpFailure("missing_subtitle_body");
            }
            String contentType = response.header("Content-Type", "")
                    .toLowerCase(Locale.ROOT);
            if (contentType.contains("text/html")
                    || contentType.contains("application/json")) {
                throw new HttpFailure("invalid_subtitle_body");
            }
            try (InputStream input = body.byteStream();
                 FileOutputStream fileOutput = new FileOutputStream(output)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                boolean firstChunk = true;
                while (true) {
                    if (!isCurrent(operation) || Thread.currentThread().isInterrupted()) {
                        throw new IOException("cancelled");
                    }
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    total += count;
                    if (total > MAX_SUBTITLE_BYTES) {
                        throw new HttpFailure("subtitle_too_large");
                    }
                    if (firstChunk) {
                        firstChunk = false;
                        for (int index = 0; index < count; index++) {
                            if (buffer[index] == 0) {
                                throw new HttpFailure("invalid_subtitle_body");
                            }
                        }
                    }
                    fileOutput.write(buffer, 0, count);
                }
                if (total == 0) {
                    throw new HttpFailure("subtitle_empty");
                }
            }
            success = true;
            return output;
        } finally {
            activeCalls.remove(call);
            if (!success) {
                deleteQuietly(output);
            }
        }
    }

    private static MediaItem.SubtitleConfiguration buildConfiguration(
            Candidate candidate, File file) {
        int roleFlags = C.ROLE_FLAG_SUBTITLE
                | (candidate.hearingImpaired ? C.ROLE_FLAG_CAPTION : 0);
        int selectionFlags = candidate.forced ? C.SELECTION_FLAG_FORCED : 0;
        String id = TRACK_ID_PREFIX + candidate.fileId;
        String label = buildLabel(candidate);
        SubtitleTrackIdentity.registerVerifiedOpenSubtitlesMatch(
                id,
                candidate.language,
                selectionFlags,
                roleFlags,
                label);
        return new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setId(id)
                .setLanguage(candidate.language)
                .setLabel(label)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setRoleFlags(roleFlags)
                .setSelectionFlags(selectionFlags)
                .build();
    }

    private static String buildLabel(Candidate candidate) {
        String detail = !candidate.release.isEmpty()
                ? candidate.release : candidate.filename;
        StringBuilder label = new StringBuilder("OpenSubtitles · ")
                .append(candidate.language.toUpperCase(Locale.ROOT));
        if (!detail.isEmpty()) {
            label.append(" · ").append(cleanLabel(detail));
        }
        if (label.length() > 140) {
            label.setLength(139);
            label.append('…');
        }
        return label.toString();
    }

    static List<Candidate> parseExactCandidates(String json, String[] preferredLanguages)
            throws JSONException {
        return parseCandidates(json, preferredLanguages, null, true);
    }

    static List<Candidate> parseLikelyCandidates(
            String json,
            String[] preferredLanguages,
            @Nullable String mediaFilename) throws JSONException {
        if (releaseQuery(mediaFilename).isEmpty()) {
            return new ArrayList<>();
        }
        return parseCandidates(json, preferredLanguages, mediaFilename, false);
    }

    private static SearchPageStats analyzeSearchPage(
            String json,
            String[] preferredLanguages,
            @Nullable String mediaFilename) throws JSONException {
        JSONObject response = new JSONObject(json);
        JSONArray data = response.optJSONArray("data");
        int rawResults = data == null ? 0 : Math.min(data.length(), MAX_RESULTS);
        int totalPages = Math.max(1, response.optInt("total_pages", 1));
        int totalCount = Math.max(rawResults, response.optInt("total_count", rawResults));
        LinkedHashMap<String, Integer> ranks = languageRanks(preferredLanguages);
        int preferredResults = 0;
        int fileResults = 0;
        int bestScore = 0;
        String bestReason = releaseQuery(mediaFilename).isEmpty()
                ? "filename_unavailable" : "no_release_metadata";
        String bestCandidate = "";
        if (data == null) {
            return new SearchPageStats(
                    0, 0, 0, totalPages, totalCount,
                    bestScore, bestReason, bestCandidate);
        }
        for (int index = 0; index < rawResults; index++) {
            JSONObject item = data.optJSONObject(index);
            JSONObject attributes = item == null
                    ? null : item.optJSONObject("attributes");
            if (attributes == null) {
                continue;
            }
            String language = OpenSubtitlesV3Client.normalizeLanguage(
                    attributes.optString("language", ""));
            if (!ranks.containsKey(language)) {
                continue;
            }
            preferredResults++;
            JSONArray files = attributes.optJSONArray("files");
            if (files == null) {
                continue;
            }
            fileResults += Math.min(files.length(), 20);
            if (isMovieHashMatch(attributes) || releaseQuery(mediaFilename).isEmpty()) {
                continue;
            }
            String release = cleanLabel(attributes.optString("release", ""));
            OpenSubtitlesV3Client.ReleaseMatch releaseMatch =
                    OpenSubtitlesV3Client.evaluateReleaseMatch(mediaFilename, release);
            if (!release.isEmpty()
                    && (bestCandidate.isEmpty() || releaseMatch.score > bestScore)) {
                bestScore = releaseMatch.score;
                bestReason = releaseMatch.reason;
                bestCandidate = diagnosticText(release);
            }
            int fileLimit = Math.min(files.length(), 20);
            for (int fileIndex = 0; fileIndex < fileLimit; fileIndex++) {
                JSONObject file = files.optJSONObject(fileIndex);
                String filename = cleanLabel(
                        file == null ? "" : file.optString("file_name", ""));
                OpenSubtitlesV3Client.ReleaseMatch fileMatch =
                        OpenSubtitlesV3Client.evaluateReleaseMatch(mediaFilename, filename);
                if (!filename.isEmpty()
                        && (bestCandidate.isEmpty() || fileMatch.score > bestScore)) {
                    bestScore = fileMatch.score;
                    bestReason = fileMatch.reason;
                    bestCandidate = diagnosticText(filename);
                }
            }
        }
        return new SearchPageStats(
                rawResults, preferredResults, fileResults, totalPages, totalCount,
                bestScore, bestReason, bestCandidate);
    }

    private static List<Candidate> parseCandidates(
            String json,
            String[] preferredLanguages,
            @Nullable String mediaFilename,
            boolean exactOnly) throws JSONException {
        LinkedHashMap<String, Integer> languageRanks = languageRanks(preferredLanguages);
        JSONArray data = new JSONObject(json).optJSONArray("data");
        if (data == null || languageRanks.isEmpty()) {
            return new ArrayList<>();
        }
        List<Candidate> result = new ArrayList<>();
        Set<String> seenFiles = new HashSet<>();
        int length = Math.min(data.length(), MAX_RESULTS);
        for (int index = 0; index < length; index++) {
            JSONObject item = data.optJSONObject(index);
            JSONObject attributes = item == null
                    ? null : item.optJSONObject("attributes");
            boolean exactMatch = attributes != null && isMovieHashMatch(attributes);
            if (attributes == null || exactOnly != exactMatch) {
                continue;
            }
            String language = OpenSubtitlesV3Client.normalizeLanguage(
                    attributes.optString("language", ""));
            Integer languageRank = languageRanks.get(language);
            if (languageRank == null) {
                continue;
            }
            JSONArray files = attributes.optJSONArray("files");
            if (files == null || files.length() == 0) {
                continue;
            }
            String release = cleanLabel(attributes.optString("release", ""));
            int fileLimit = Math.min(files.length(), 20);
            for (int fileIndex = 0; fileIndex < fileLimit; fileIndex++) {
                JSONObject file = files.optJSONObject(fileIndex);
                String fileId = numericString(file == null ? null : file.opt("file_id"));
                if (fileId.isEmpty() || !seenFiles.add(fileId)) {
                    continue;
                }
                String filename = cleanLabel(file.optString("file_name", ""));
                int releaseScore = exactMatch
                        ? 100 : Math.max(
                                OpenSubtitlesV3Client.releaseMatchScore(
                                        mediaFilename, release),
                                OpenSubtitlesV3Client.releaseMatchScore(
                                        mediaFilename, filename));
                if (!exactMatch
                        && !OpenSubtitlesV3Client.isLikelyReleaseMatch(
                                mediaFilename, release)
                        && !OpenSubtitlesV3Client.isLikelyReleaseMatch(
                                mediaFilename, filename)) {
                    continue;
                }
                Set<String> identifiers = new LinkedHashSet<>();
                addIdentifier(identifiers, fileId);
                addIdentifier(identifiers, numericString(attributes.opt("subtitle_id")));
                addIdentifier(identifiers, numericString(
                        attributes.opt("legacy_subtitle_id")));
                addIdentifier(identifiers, item.optString("id", ""));
                result.add(new Candidate(
                        fileId,
                        identifiers,
                        language,
                        release,
                        filename,
                        languageRank,
                        attributes.optBoolean("hearing_impaired", false),
                        attributes.optBoolean("foreign_parts_only", false),
                        attributes.optBoolean("from_trusted", false),
                        Math.max(0, attributes.optInt("download_count", 0)),
                        releaseScore));
            }
        }
        sortCandidates(result);
        return result;
    }

    private static List<Candidate> deduplicateCandidates(List<Candidate> candidates) {
        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            Candidate previous = unique.get(candidate.fileId);
            if (previous == null || candidate.releaseScore > previous.releaseScore) {
                unique.put(candidate.fileId, candidate);
            }
        }
        List<Candidate> result = new ArrayList<>(unique.values());
        sortCandidates(result);
        return result;
    }

    private static void sortCandidates(List<Candidate> candidates) {
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate first, Candidate second) {
                int comparison = Integer.compare(first.languageRank, second.languageRank);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(second.releaseScore, first.releaseScore);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(first.forced ? 1 : 0, second.forced ? 1 : 0);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(
                        first.hearingImpaired ? 1 : 0,
                        second.hearingImpaired ? 1 : 0);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(first.trusted ? 0 : 1, second.trusted ? 0 : 1);
                if (comparison != 0) return comparison;
                return Integer.compare(second.downloads, first.downloads);
            }
        });
    }

    static int verifyExisting(
            List<MediaItem.SubtitleConfiguration> existing,
            List<Candidate> candidates,
            Set<Candidate> matchedCandidates) {
        int verified = 0;
        for (MediaItem.SubtitleConfiguration configuration : existing) {
            if (!SubtitleTrackIdentity.isOpenSubtitles(
                    configuration.id, configuration.label)) {
                continue;
            }
            Set<String> identifiers = configurationIdentifiers(configuration);
            Candidate match = null;
            for (Candidate candidate : candidates) {
                if (candidate.language.equals(OpenSubtitlesV3Client.normalizeLanguage(
                        configuration.language))
                        && intersects(identifiers, candidate.identifiers)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            SubtitleTrackIdentity.registerVerifiedOpenSubtitlesMatch(
                    configuration.id,
                    configuration.language,
                    configuration.selectionFlags,
                    configuration.roleFlags,
                    configuration.label);
            matchedCandidates.add(match);
            verified++;
        }
        return verified;
    }

    static int verifyLikelyExisting(
            List<MediaItem.SubtitleConfiguration> existing,
            List<Candidate> candidates) {
        int verified = 0;
        for (MediaItem.SubtitleConfiguration configuration : existing) {
            Candidate match = findLikelyMatch(configuration, candidates);
            if (match == null) {
                continue;
            }
            SubtitleTrackIdentity.registerOpenSubtitlesMatch(
                    configuration.id,
                    configuration.language,
                    configuration.selectionFlags,
                    configuration.roleFlags,
                    configuration.label,
                    OpenSubtitlesV3Client.MatchConfidence.LIKELY.rank);
            verified++;
        }
        return verified;
    }

    @Nullable
    static Candidate findLikelyMatch(
            @Nullable String trackId,
            @Nullable String label,
            @Nullable String language,
            Set<String> identifiers,
            List<Candidate> candidates) {
        Set<String> releaseNames = new LinkedHashSet<>();
        addVisibleReleaseName(releaseNames, releaseNameFromLabel(label));
        return findLikelyMatch(
                trackId, label, language, identifiers, releaseNames, candidates);
    }

    @Nullable
    private static Candidate findLikelyMatch(
            MediaItem.SubtitleConfiguration configuration,
            List<Candidate> candidates) {
        Set<String> releaseNames = new LinkedHashSet<>();
        addVisibleReleaseName(releaseNames, releaseNameFromLabel(configuration.label));
        addVisibleReleaseName(releaseNames, configuration.uri.getLastPathSegment());
        return findLikelyMatch(
                configuration.id,
                configuration.label,
                configuration.language,
                configurationIdentifiers(configuration),
                releaseNames,
                candidates);
    }

    @Nullable
    private static Candidate findLikelyMatch(
            @Nullable String trackId,
            @Nullable String label,
            @Nullable String language,
            Set<String> identifiers,
            Set<String> releaseNames,
            List<Candidate> candidates) {
        if (!SubtitleTrackIdentity.isOpenSubtitles(trackId, label)) {
            return null;
        }
        String normalizedLanguage = OpenSubtitlesV3Client.normalizeLanguage(language);
        for (Candidate candidate : candidates) {
            if (candidate.language.equals(normalizedLanguage)
                    && intersects(identifiers, candidate.identifiers)) {
                return candidate;
            }
        }

        Candidate best = null;
        int bestScore = 0;
        for (Candidate candidate : candidates) {
            if (!candidate.language.equals(normalizedLanguage)) {
                continue;
            }
            for (String releaseName : releaseNames) {
                int releaseScore = OpenSubtitlesV3Client.releaseMatchScore(
                        releaseName, candidate.release);
                int filenameScore = OpenSubtitlesV3Client.releaseMatchScore(
                        releaseName, candidate.filename);
                int score = Math.max(releaseScore, filenameScore);
                boolean likely = OpenSubtitlesV3Client.isLikelyReleaseMatch(
                        releaseName, candidate.release)
                        || OpenSubtitlesV3Client.isLikelyReleaseMatch(
                                releaseName, candidate.filename);
                if (likely && score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static String releaseNameFromLabel(@Nullable String label) {
        if (label == null) {
            return "";
        }
        int separator = label.lastIndexOf('·');
        String detail = separator >= 0 && separator + 1 < label.length()
                ? label.substring(separator + 1) : label;
        return cleanLabel(detail);
    }

    private static void addVisibleReleaseName(Set<String> target, @Nullable String value) {
        String cleaned = cleanLabel(value);
        if (!releaseQuery(cleaned).isEmpty() && !cleaned.matches("[0-9]+")) {
            target.add(cleaned);
        }
    }

    private static Set<String> configurationIdentifiers(
            MediaItem.SubtitleConfiguration configuration) {
        Set<String> result = new LinkedHashSet<>();
        addIdentifier(result, encodedTrackIdentifier(configuration.id));
        if (configuration.label != null) {
            String detail = configuration.label;
            int separator = detail.lastIndexOf('·');
            if (separator >= 0 && separator + 1 < detail.length()) {
                detail = detail.substring(separator + 1);
            }
            for (String part : detail.split("[^0-9]+")) {
                addIdentifier(result, part);
            }
        }
        for (String segment : configuration.uri.getPathSegments()) {
            addIdentifier(result, segment);
        }
        return result;
    }

    static String encodedTrackIdentifier(@Nullable String trackId) {
        String canonical = SubtitleTrackIdentity.canonicalId(trackId);
        String marker = OpenSubtitlesV3Client.TRACK_ID_PREFIX + "osid-";
        if (!canonical.startsWith(marker)) {
            return "";
        }
        int start = marker.length();
        int end = canonical.indexOf('-', start);
        if (end <= start) {
            return "";
        }
        return numericString(canonical.substring(start, end));
    }

    private static boolean intersects(Set<String> first, Set<String> second) {
        for (String value : first) {
            if (second.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMovieHashMatch(JSONObject attributes) {
        return attributes.optBoolean("moviehash_match", false)
                || "moviehash".equalsIgnoreCase(
                attributes.optString("matched_by", ""));
    }

    private static LinkedHashMap<String, Integer> languageRanks(String[] languages) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String language : languages) {
            String normalized = OpenSubtitlesV3Client.normalizeLanguage(language);
            if (!normalized.isEmpty() && !result.containsKey(normalized)) {
                result.put(normalized, result.size());
            }
        }
        return result;
    }

    private static String apiLanguages(String[] languages) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String language : languages) {
            String normalized = OpenSubtitlesV3Client.normalizeLanguage(language);
            String apiLanguage = toApiLanguage(normalized);
            if (!apiLanguage.isEmpty()) {
                result.add(apiLanguage);
            }
        }
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        StringBuilder joined = new StringBuilder();
        for (String language : sorted) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(language);
        }
        return joined.toString();
    }

    private static String imdbDigits(@Nullable String value) {
        if (value == null || !value.matches("tt\\d+")) {
            return "";
        }
        return trimLeadingZeros(value.substring(2));
    }

    private static String releaseQuery(@Nullable String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("file_[a-f0-9]{8,}(\\.[a-z0-9]{2,5})?")
                || normalized.matches("[a-f0-9]{16,}(\\.[a-z0-9]{2,5})?")) {
            return "";
        }
        normalized = normalized
                .replaceAll("\\.(mkv|mp4|avi|mov|m4v)$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.length() < 3 ? "" : normalized.replace(" ", "+");
    }

    private static String toApiLanguage(String language) {
        switch (language) {
            case "ces": return "cs";
            case "slk": return "sk";
            case "eng": return "en";
            case "deu": return "de";
            case "fra": return "fr";
            case "spa": return "es";
            case "ita": return "it";
            case "pol": return "pl";
            case "por": return "pt";
            case "hun": return "hu";
            case "rus": return "ru";
            case "ukr": return "uk";
            default: return language.matches("[a-z]{2}") ? language : "";
        }
    }

    private static Request.Builder apiRequest(HttpUrl url, String apiKey) {
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .header("User-Agent", USER_AGENT);
    }

    private static HttpUrl apiUrl(String path) {
        return new Request.Builder().url(API_BASE).build().url()
                .newBuilder().addPathSegment(path).build();
    }

    private static String httpReason(int code) {
        if (code == 401 || code == 403) {
            return "unauthorized";
        }
        if (code == 406) {
            return "download_not_available";
        }
        if (code == 429) {
            return "rate_limited";
        }
        return "http_" + code;
    }

    private static String numericString(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.matches("[0-9]{1,20}") ? trimLeadingZeros(text) : "";
    }

    private static void addIdentifier(Set<String> target, @Nullable String value) {
        String numeric = numericString(value);
        if (!numeric.isEmpty()) {
            target.add(numeric);
        }
    }

    private static String trimLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    private static String cleanLabel(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String filenameDiagnostic(@Nullable String value) {
        if (releaseQuery(value).isEmpty()) {
            return value == null || value.trim().isEmpty()
                    ? "unavailable" : "synthetic";
        }
        return diagnosticText(value);
    }

    private static String diagnosticText(@Nullable String value) {
        String cleaned = cleanLabel(value);
        if (cleaned.isEmpty()) {
            return "none";
        }
        return cleaned.length() <= 96
                ? cleaned : cleaned.substring(0, 95) + "…";
    }

    private static String subtitleSuffix(@Nullable String filename) {
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".srt")) return ".srt";
            if (lower.endsWith(".vtt")) return ".vtt";
            if (lower.endsWith(".ass")) return ".ass";
            if (lower.endsWith(".ssa")) return ".ssa";
        }
        return ".srt";
    }

    static TestResult testCredentials(
            OkHttpClient client,
            OpenSubtitlesCredentialsStore.Credentials credentials) {
        if (credentials == null || !credentials.isValid()) {
            return new TestResult(false, false, "not_configured");
        }
        OpenSubtitlesRestClient temporary = null;
        try {
            temporary = new OpenSubtitlesRestClient(client);
            Request search = credentialsTestRequest(credentials.apiKey);
            temporary.executeJson(temporary.operationToken, search);
            if (credentials.hasAccount()) {
                temporary.login(temporary.operationToken, credentials);
                return new TestResult(true, true, "");
            }
            return new TestResult(true, false, "");
        } catch (HttpFailure error) {
            return new TestResult(false, false, error.reason);
        } catch (IOException | JSONException | RuntimeException error) {
            return new TestResult(false, false, "network_or_response");
        } finally {
            if (temporary != null) {
                temporary.release();
            }
        }
    }

    /** Avoids creating an Android cache dependency for the settings connection test. */
    private OpenSubtitlesRestClient(OkHttpClient client) {
        context = null;
        mediaHttpClient = client;
        apiHttpClient = apiClient(client);
    }

    private static OkHttpClient apiClient(OkHttpClient base) {
        return base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    synchronized void cancel() {
        operationToken++;
        Future<?> task = activeTask;
        activeTask = null;
        if (task != null) {
            task.cancel(true);
        }
        synchronized (activeCalls) {
            for (Call call : activeCalls) {
                call.cancel();
            }
            activeCalls.clear();
        }
    }

    synchronized void release() {
        if (released) {
            return;
        }
        cancel();
        released = true;
        executor.shutdownNow();
        // Keep files that may still be consumed by the current MediaItem. They are in the app
        // cache and the next client instance removes abandoned files after 24 hours.
    }

    private boolean isCurrent(long token) {
        return !released && token == operationToken
                && !Thread.currentThread().isInterrupted();
    }

    private void deleteAbandonedCacheFiles() {
        File[] files = context.getCacheDir().listFiles(
                (directory, name) -> name.startsWith("opensubtitles-rest-"));
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                deleteQuietly(file);
            }
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final class HttpFailure extends Exception {
        final String reason;

        HttpFailure(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
