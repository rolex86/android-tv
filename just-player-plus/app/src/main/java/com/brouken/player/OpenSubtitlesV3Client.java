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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Loads and release-ranks subtitles exposed by the public Stremio OpenSubtitles v3 add-on. */
final class OpenSubtitlesV3Client {
    static final String TRACK_ID_PREFIX = SmartSubtitleSelector.EXTERNAL_ID_PREFIX
            + "opensubtitles-v3:";
    private static final String BASE_URL = "https://opensubtitles-v3.strem.io/";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SERVER_RESULTS = 500;
    private static final int MAX_LABEL_LENGTH = 140;
    private static final int LIKELY_RELEASE_THRESHOLD = 45;

    enum MatchConfidence {
        LIKELY(1), UNKNOWN(2);

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
        final String identifier;
        final String language;
        final String label;
        final String mimeType;
        final int roleFlags;
        final int selectionFlags;
        final MatchConfidence confidence;
        final int languageRank;
        final int sourceOrder;

        Candidate(String url,
                  String identifier,
                  String language,
                  String label,
                  String mimeType,
                  int roleFlags,
                  int selectionFlags,
                  MatchConfidence confidence,
                  int languageRank,
                  int sourceOrder) {
            this.url = url;
            this.identifier = identifier;
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

    static final class ReleaseMatch {
        final int score;
        final String reason;

        ReleaseMatch(int score, String reason) {
            this.score = Math.max(0, Math.min(100, score));
            this.reason = reason;
        }
    }

    private final OkHttpClient httpClient;
    @Nullable private volatile Call activeInitialCall;
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

        final String filename = mediaFilename == null ? "" : mediaFilename;
        Request request = new Request.Builder()
                .url(genericUrl(type, id, filename))
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
                        activeInitialCall = null;
                        listener.onFailure("http_" + closeable.code());
                        return;
                    }
                    ResponseBody body = closeable.body();
                    if (body == null) {
                        activeInitialCall = null;
                        listener.onFailure("invalid_body");
                        return;
                    }
                    String json = BoundedResponseBody.readUtf8(body, MAX_RESPONSE_BYTES);
                    List<Candidate> genericCandidates = parseCandidatesInternal(
                            json, languages, MatchConfidence.UNKNOWN);
                    List<MediaItem.SubtitleConfiguration> configurations =
                            buildConfigurationsIfCurrent(
                                    token,
                                    sortAndLimit(new ArrayList<>(genericCandidates)));
                    if (configurations == null) {
                        return;
                    }
                    activeInitialCall = null;
                    if (isCurrent(token)) {
                        listener.onLoaded(configurations);
                    }
                } catch (BoundedResponseBody.ResponseTooLargeException error) {
                    if (isCurrent(token)) {
                        activeInitialCall = null;
                        listener.onFailure("response_too_large");
                    }
                } catch (IOException | JSONException | RuntimeException error) {
                    if (isCurrent(token)) {
                        activeInitialCall = null;
                        listener.onFailure("invalid_response");
                    }
                }
            }
        });
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

    private static void addLanguage(Set<String> target, @Nullable String language) {
        String normalized = normalizeLanguage(language);
        if (!normalized.isEmpty() && !"und".equals(normalized)) {
            target.add(normalized);
        }
    }

    static HttpUrl genericUrl(String type, String id, @Nullable String filename) {
        HttpUrl.Builder builder = baseUrl().newBuilder()
                .addPathSegment("subtitles")
                .addPathSegment(type);
        if (filename == null || filename.trim().isEmpty()) {
            return builder.addPathSegment(id + ".json").build();
        }
        String extra = "filename=" + Uri.encode(filename.trim()) + ".json";
        return builder.addPathSegment(id).addEncodedPathSegment(extra).build();
    }

    private static HttpUrl baseUrl() {
        return new Request.Builder().url(BASE_URL).build().url();
    }

    synchronized void cancel() {
        operationToken++;

        Call initial = activeInitialCall;
        activeInitialCall = null;
        if (initial != null) {
            initial.cancel();
        }
    }

    synchronized void release() {
        if (released) {
            return;
        }
        cancel();
        released = true;
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

    static boolean isSupportedContent(@Nullable String type, @Nullable String id) {
        if (id == null) {
            return false;
        }
        if ("movie".equals(type)) {
            return id.matches("tt\\d+");
        }
        if ("series".equals(type)) {
            return id.matches("tt\\d+:\\d+:\\d+");
        }
        return false;
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
                if (SubtitleTrackIdentity.isOpenSubtitles(format.id, format.label)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<Candidate> parseCandidates(String json, String[] languages)
            throws JSONException {
        return sortAndLimit(parseCandidatesInternal(
                json, languages, MatchConfidence.UNKNOWN));
    }

    private static List<Candidate> parseCandidatesInternal(
            String json,
            String[] languages,
            MatchConfidence defaultConfidence) throws JSONException {
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

            result.add(new Candidate(
                    url,
                    identifier,
                    language,
                    buildLabel(language, identifier, name),
                    mimeType,
                    roleFlags,
                    selectionFlags,
                    defaultConfidence,
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
            String numericIdentifier = numericIdentifier(candidate.identifier);
            String id = TRACK_ID_PREFIX
                    + (numericIdentifier.isEmpty()
                    ? "" : "osid-" + numericIdentifier + '-')
                    + shortHash(candidate.url);
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

    private static String numericIdentifier(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[0-9]{1,20}")) {
            return "";
        }
        int index = 0;
        while (index < trimmed.length() - 1 && trimmed.charAt(index) == '0') {
            index++;
        }
        return trimmed.substring(index);
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
            return 10;
        }
        if (rank == 2) {
            return 5;
        }
        return 3;
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
        return releaseMatchScore(media, candidate) >= LIKELY_RELEASE_THRESHOLD;
    }

    /**
     * Conservative metadata confidence used only after the OpenSubtitles result has already been
     * scoped to the current IMDb movie or episode. This is not a fuzzy movie-hash comparison.
     */
    static int releaseMatchScore(@Nullable String media, @Nullable String candidate) {
        return evaluateReleaseMatch(media, candidate).score;
    }

    static ReleaseMatch evaluateReleaseMatch(
            @Nullable String media, @Nullable String candidate) {
        String normalizedMedia = normalizeReleaseName(media);
        String normalizedCandidate = normalizeReleaseName(candidate);
        if (normalizedMedia.isEmpty() || normalizedCandidate.isEmpty()) {
            return new ReleaseMatch(0, "missing_name");
        }
        if (normalizedMedia.equals(normalizedCandidate)) {
            return new ReleaseMatch(100, "exact_name");
        }
        Set<String> mediaTokens = releaseTokens(media);
        Set<String> candidateTokens = releaseTokens(candidate);
        if (mediaTokens.size() < 3 || candidateTokens.isEmpty()) {
            return new ReleaseMatch(0, "insufficient_tokens");
        }
        if (conflictsCategory(mediaTokens, candidateTokens, EDITIONS)) {
            return new ReleaseMatch(0, "edition_conflict");
        }

        String mediaGroup = releaseGroup(media);
        String candidateGroup = releaseGroup(candidate);
        int score = 0;
        StringBuilder reason = new StringBuilder();
        if (!mediaGroup.isEmpty() && mediaGroup.equals(candidateGroup)) {
            score += 35;
            appendReason(reason, "group");
        } else if (!mediaGroup.isEmpty() && !candidateGroup.isEmpty()) {
            score -= 12;
            appendReason(reason, "group_conflict");
        }
        if (sharesCategory(mediaTokens, candidateTokens, RESOLUTIONS)) {
            score += 10;
            appendReason(reason, "resolution");
        } else if (conflictsCategory(mediaTokens, candidateTokens, RESOLUTIONS)) {
            score -= 6;
            appendReason(reason, "resolution_conflict");
        }
        if (sharesSourceFamily(mediaTokens, candidateTokens)) {
            score += 18;
            appendReason(reason, "source");
        } else if (conflictsSourceFamily(mediaTokens, candidateTokens)) {
            score -= 18;
            appendReason(reason, "source_conflict");
        }
        if (sharesCategory(mediaTokens, candidateTokens, CODECS)) {
            score += 8;
            appendReason(reason, "codec");
        }
        if (sharesCategory(mediaTokens, candidateTokens, HDR_FORMATS)) {
            score += 4;
            appendReason(reason, "hdr");
        }
        if (sharesCategory(mediaTokens, candidateTokens, EDITIONS)) {
            score += 10;
            appendReason(reason, "edition");
        }

        int ordinaryShared = 0;
        int extraTechnicalShared = 0;
        for (String token : mediaTokens) {
            if (candidateTokens.contains(token)) {
                if (isDistinctive(token)) {
                    extraTechnicalShared++;
                } else {
                    ordinaryShared++;
                }
            }
        }
        int technicalScore = Math.min(15, extraTechnicalShared * 3);
        int ordinaryScore = Math.min(18, ordinaryShared * 3);
        score += technicalScore + ordinaryScore;
        if (technicalScore > 0) {
            appendReason(reason, "technical");
        }
        if (ordinaryScore > 0) {
            appendReason(reason, "tokens");
        }
        return new ReleaseMatch(score, reason.length() == 0 ? "no_evidence" : reason.toString());
    }

    private static void appendReason(StringBuilder target, String value) {
        if (target.length() > 0) {
            target.append(',');
        }
        target.append(value);
    }

    private static Set<String> releaseTokens(@Nullable String value) {
        String normalized = normalizeReleaseName(value)
                .replace("web-dl", "webdl")
                .replace("web dl", "webdl")
                .replace("webdlrip", "webdl")
                .replace("bd-remux", "bdremux")
                .replace("bd remux", "bdremux")
                .replace("bd-rip", "bdrip")
                .replace("bd rip", "bdrip")
                .replace("blu-ray", "bluray")
                .replace("blu ray", "bluray")
                .replace("blurayremux", "bluray remux")
                .replace("blurayrip", "brrip")
                .replace("h.265", "hevc")
                .replace("h 265", "hevc")
                .replace("x265", "hevc")
                .replace("h265", "hevc")
                .replace("h.264", "h264")
                .replace("h 264", "h264")
                .replace("x264", "h264")
                .replace("dolby vision", "dv")
                .replace("dolbyvision", "dv")
                .replace("dovi", "dv")
                .replace("hdr10+", "hdr10")
                .replaceAll("(?<![a-z0-9])4k(?![a-z0-9])", "2160p")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String rawToken : normalized.split("\\s+")) {
            String token = normalizeReleaseToken(rawToken);
            if ((token.length() >= 3 || "dv".equals(token)) && !isNoise(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static String normalizeReleaseToken(String token) {
        switch (token) {
            case "bdremux":
                return "remux";
            case "bdrip":
                return "brrip";
            case "webdlrip":
                return "webdl";
            case "dovi":
                return "dv";
            case "dolbyvision":
                return "vision";
            case "hdr10plus":
                return "hdr10";
            case "avc":
                return "h264";
            case "nf":
                return "netflix";
            case "amzn":
                return "amazon";
            case "dsnp":
                return "disneyplus";
            case "hmax":
                return "hbomax";
            case "atvp":
                return "appletv";
            case "pcok":
                return "peacock";
            case "pmnt":
                return "paramount";
            default:
                return token;
        }
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
                + "proper|repack|extended|criterion|theatrical|uncut|directors|final|dv|10bit|"
                + "netflix|amazon|disneyplus|hbomax|appletv|peacock|paramount|hulu)");
    }

    private static final Set<String> RESOLUTIONS = tokenSet(
            "2160p", "1080p", "720p", "480p");
    private static final Set<String> DISC_SOURCES = tokenSet(
            "bluray", "brrip", "remux", "uhd");
    private static final Set<String> WEB_SOURCES = tokenSet("webdl", "webrip");
    private static final Set<String> TV_SOURCES = tokenSet("hdtv");
    private static final Set<String> DVD_SOURCES = tokenSet("dvdrip");
    private static final Set<String> CODECS = tokenSet(
            "h264", "hevc", "av1");
    private static final Set<String> HDR_FORMATS = tokenSet(
            "hdr", "hdr10", "dolby", "vision", "dv");
    private static final Set<String> EDITIONS = tokenSet(
            "extended", "criterion", "theatrical", "uncut", "directors", "final");

    private static boolean conflictsCategory(
            Set<String> first, Set<String> second, Set<String> category) {
        String firstValue = firstInCategory(first, category);
        String secondValue = firstInCategory(second, category);
        return !firstValue.isEmpty()
                && !secondValue.isEmpty()
                && !firstValue.equals(secondValue);
    }

    private static boolean conflictsSourceFamily(Set<String> first, Set<String> second) {
        String firstFamily = sourceFamily(first);
        String secondFamily = sourceFamily(second);
        return !firstFamily.isEmpty()
                && !secondFamily.isEmpty()
                && !firstFamily.equals(secondFamily);
    }

    private static boolean sharesSourceFamily(Set<String> first, Set<String> second) {
        String firstFamily = sourceFamily(first);
        return !firstFamily.isEmpty() && firstFamily.equals(sourceFamily(second));
    }

    private static String sourceFamily(Set<String> tokens) {
        if (containsCategory(tokens, DISC_SOURCES)) return "disc";
        if (containsCategory(tokens, WEB_SOURCES)) return "web";
        if (containsCategory(tokens, TV_SOURCES)) return "tv";
        if (containsCategory(tokens, DVD_SOURCES)) return "dvd";
        return "";
    }

    private static boolean sharesCategory(
            Set<String> first, Set<String> second, Set<String> category) {
        for (String value : category) {
            if (first.contains(value) && second.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCategory(Set<String> tokens, Set<String> category) {
        return !firstInCategory(tokens, category).isEmpty();
    }

    private static String firstInCategory(Set<String> tokens, Set<String> category) {
        for (String value : category) {
            if (tokens.contains(value)) {
                return value;
            }
        }
        return "";
    }

    private static Set<String> tokenSet(String... values) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static String normalizeReleaseName(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?i)(?:\\.(?:mkv|mp4|avi|mov|m4v|srt|vtt|ass|ssa|sub"
                        + "|en|eng|cs|cze|ces|sk|slo|slk|de|ger|deu|fr|fre|fra"
                        + "|es|spa|it|ita|pl|pol|pt|por|hu|hun|ru|rus|uk|ukr))+$", "")
                .replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .trim();
    }

    private static String releaseGroup(@Nullable String value) {
        String normalized = normalizeReleaseName(value);
        int dash = normalized.lastIndexOf('-');
        if (dash < 0 || dash == normalized.length() - 1) {
            return "";
        }
        String group = normalized.substring(dash + 1);
        if (!group.matches("[a-z0-9]{2,24}")
                || "dl".equals(group)
                || "ray".equals(group)
                || "rip".equals(group)) {
            return "";
        }
        return group;
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
