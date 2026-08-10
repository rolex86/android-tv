package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filters, deduplicates, orders and labels standard Stremio stream objects. */
final class StremioStreamPipeline {
    private static final int ABSOLUTE_MAX_CANDIDATES = 5_000;
    private static final int ABSOLUTE_MAX_RESULTS = 1_000;
    private static final int MAX_STREAM_OBJECT_CHARS = 256 * 1024;
    private static final Pattern RESOLUTION_2160 = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:2160p?|4k|uhd)(?:$|[^a-z0-9])");
    private static final Pattern RESOLUTION_1080 = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:1080p?|full[ ._-]?hd|fhd)(?:$|[^a-z0-9])");
    private static final Pattern RESOLUTION_720 = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:720p?|hd)(?:$|[^a-z0-9])");
    private static final Pattern RESOLUTION_SD = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:480p?|576p?|sd)(?:$|[^a-z0-9])");
    private static final Pattern SIZE = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(tib|tb|gib|gb|mib|mb)(?:$|[^a-z])");
    private static final Pattern HEVC = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:hevc|h[ ._-]?265|x265)(?:$|[^a-z0-9])");
    private static final Pattern H264 = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:avc|h[ ._-]?264|x264)(?:$|[^a-z0-9])");
    private static final Pattern AV1 = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])av1(?:$|[^a-z0-9])");
    private static final Pattern OTHER_CODEC = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:vp9|mpeg[ ._-]?2|xvid|divx)(?:$|[^a-z0-9])");
    private static final Pattern DOLBY_VISION = Pattern.compile(
            "(?i)(?:dolby[ ._-]?vision|dovi|dv(?:$|[ ._+|/\\-]))");
    private static final Pattern HDR = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:hdr10\\+?|hdr)(?:$|[^a-z0-9])");
    private static final Pattern SDR = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])sdr(?:$|[^a-z0-9])");
    private static final Pattern BAD_UNCACHED = Pattern.compile(
            "(?i)(?:un[ ._-]?cached|not[ ._-]?cached)");
    private static final Pattern CACHED = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:cached|cache|ready|instant)(?:$|[^a-z0-9])");

    private static final Map<String, String> LANGUAGE_ALIASES = languageAliases();
    private static final List<String> QUALITY_ORDER =
            Arrays.asList("2160p", "1080p", "720p", "sd", "unknown");

    static final class SourceStreams {
        @NonNull final StremioStreamSourceStore.Source source;
        @NonNull final List<JSONObject> streams;
        final int priority;

        SourceStreams(StremioStreamSourceStore.Source source,
                      List<JSONObject> streams,
                      int priority) {
            this.source = source;
            this.streams = streams;
            this.priority = priority;
        }
    }

    static final class Result {
        @NonNull final String response;
        @NonNull final Stats stats;
        @NonNull final String state;

        Result(String response, Stats stats, String state) {
            this.response = response;
            this.stats = stats;
            this.state = state;
        }
    }

    static final class Stats {
        int raw;
        int accepted;
        int returned;
        int oversized;
        int invalid;
        int duplicate;
        int sourceLimit;
        int candidateLimit;
        int qualityLimit;
        int resultLimit;
        final Map<String, Integer> rejected = new LinkedHashMap<>();

        void reject(String reason) {
            Integer count = rejected.get(reason);
            rejected.put(reason, count == null ? 1 : count + 1);
        }

        String summary() {
            StringBuilder value = new StringBuilder()
                    .append("raw=").append(raw)
                    .append(" accepted=").append(accepted)
                    .append(" returned=").append(returned)
                    .append(" invalid=").append(invalid)
                    .append(" oversized=").append(oversized)
                    .append(" duplicate=").append(duplicate)
                    .append(" sourceLimit=").append(sourceLimit)
                    .append(" candidateLimit=").append(candidateLimit)
                    .append(" qualityLimit=").append(qualityLimit)
                    .append(" resultLimit=").append(resultLimit);
            for (Map.Entry<String, Integer> entry : rejected.entrySet()) {
                value.append(" filter_").append(entry.getKey())
                        .append('=').append(entry.getValue());
            }
            return value.toString();
        }
    }

    private StremioStreamPipeline() {
    }

    static String process(List<SourceStreams> sourceResults,
                          StremioAggregationPreferences.Snapshot settings) {
        return processDetailed(sourceResults, settings).response;
    }

    static Result processDetailed(List<SourceStreams> sourceResults,
                                  StremioAggregationPreferences.Snapshot settings) {
        Stats stats = new Stats();
        for (SourceStreams sourceResult : sourceResults) {
            stats.raw += sourceResult.streams.size();
        }
        try {
            List<Candidate> candidates = filterAndDeduplicate(
                    sourceResults, settings, stats);
            stats.accepted = candidates.size();
            List<Candidate> ordered = order(candidates, settings);
            JSONArray streams = new JSONArray();
            Map<String, Integer> qualityCounts = new HashMap<>();
            for (int index = 0; index < ordered.size(); index++) {
                Candidate candidate = ordered.get(index);
                if (streams.length() >= ABSOLUTE_MAX_RESULTS) {
                    stats.resultLimit += ordered.size() - index;
                    break;
                }
                if (settings.maxTotal > 0 && streams.length() >= settings.maxTotal) {
                    stats.resultLimit += ordered.size() - index;
                    break;
                }
                int qualityCount = qualityCounts.containsKey(candidate.resolution)
                        ? qualityCounts.get(candidate.resolution) : 0;
                if (settings.maxPerQuality > 0 && qualityCount >= settings.maxPerQuality) {
                    stats.qualityLimit++;
                    continue;
                }
                qualityCounts.put(candidate.resolution, qualityCount + 1);
                streams.put(format(candidate, settings));
            }
            stats.returned = streams.length();
            return new Result(
                    new JSONObject().put("streams", streams).toString(), stats, "loaded");
        } catch (JSONException | RuntimeException error) {
            return new Result(
                    StremioConnectorService.LEGACY_STREAM_RESPONSE,
                    stats,
                    "failed_" + error.getClass().getSimpleName());
        }
    }

    private static List<Candidate> filterAndDeduplicate(
            List<SourceStreams> sourceResults,
            StremioAggregationPreferences.Snapshot settings,
            Stats stats) throws JSONException {
        List<SourceStreams> sortedSources = new ArrayList<>(sourceResults);
        Collections.sort(sortedSources, (first, second) ->
                Integer.compare(first.priority, second.priority));
        List<Candidate> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> sourceCounts = new HashMap<>();
        int processed = 0;
        candidateLoop:
        for (SourceStreams sourceResult : sortedSources) {
            for (int index = 0; index < sourceResult.streams.size(); index++) {
                if (accepted.size() >= ABSOLUTE_MAX_CANDIDATES) {
                    stats.candidateLimit = Math.max(0, stats.raw - processed);
                    break candidateLoop;
                }
                processed++;
                JSONObject stream = sourceResult.streams.get(index);
                String encoded = stream.toString();
                if (encoded.length() > MAX_STREAM_OBJECT_CHARS) {
                    stats.oversized++;
                    continue;
                }
                Candidate candidate = Candidate.parse(
                        new JSONObject(encoded), sourceResult, index);
                if (candidate == null) {
                    stats.invalid++;
                    continue;
                }
                String rejection = rejectionReason(candidate, settings);
                if (rejection != null) {
                    stats.reject(rejection);
                    continue;
                }
                int sourceCount = sourceCounts.containsKey(candidate.source.id)
                        ? sourceCounts.get(candidate.source.id) : 0;
                if (settings.maxPerSource > 0 && sourceCount >= settings.maxPerSource) {
                    stats.sourceLimit++;
                    continue;
                }
                if (!"off".equals(settings.deduplication)) {
                    List<String> keys = candidate.deduplicationKeys(
                            "extended".equals(settings.deduplication));
                    boolean duplicate = false;
                    for (String key : keys) {
                        if (seen.contains(key)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) {
                        stats.duplicate++;
                        continue;
                    }
                    seen.addAll(keys);
                }
                sourceCounts.put(candidate.source.id, sourceCount + 1);
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    @Nullable
    private static String rejectionReason(
            Candidate value,
            StremioAggregationPreferences.Snapshot settings) {
        if (!settings.resolutions.contains(value.resolution)) {
            return "resolution";
        }
        if (!settings.streamTypes.contains(value.streamType)) {
            return "streamType";
        }
        for (String blocked : settings.blockedReleases) {
            if (value.releaseTypes.contains(blocked)) {
                return "release";
            }
        }
        if (value.codec.isEmpty()) {
            if (!settings.keepUnknownTech) {
                return "codecUnknown";
            }
        } else if (!settings.codecs.contains(value.codec)) {
            return "codec";
        }
        if (value.hdr.isEmpty()) {
            if (!settings.keepUnknownTech) {
                return "hdrUnknown";
            }
        } else if (!settings.hdrFormats.contains(value.hdr)) {
            return "hdr";
        }
        if (!settings.allowedLanguages.isEmpty()) {
            if (value.languages.isEmpty()) {
                if (!settings.keepUnknownLanguage) {
                    return "languageUnknown";
                }
            } else if (Collections.disjoint(value.languages, settings.allowedLanguages)) {
                return "language";
            }
        }
        if (value.sizeBytes <= 0L) {
            if (!settings.keepUnknownSize
                    && (settings.minSizeGb > 0d || settings.maxSizeGb > 0d)) {
                return "sizeUnknown";
            }
        } else {
            double sizeGb = value.sizeBytes / 1_000_000_000d;
            if (settings.minSizeGb > 0d && sizeGb < settings.minSizeGb) {
                return "sizeMin";
            }
            if (settings.maxSizeGb > 0d && sizeGb > settings.maxSizeGb) {
                return "sizeMax";
            }
        }
        for (String blocked : settings.blockedText) {
            if (value.searchText.contains(blocked)) {
                return "text";
            }
        }
        return null;
    }

    private static List<Candidate> order(List<Candidate> values,
                                         StremioAggregationPreferences.Snapshot settings) {
        Comparator<Candidate> preferences = preferenceComparator(settings);
        if ("quality_interleaved".equals(settings.sortMode)) {
            return interleaveByQuality(values, preferences);
        }
        List<Candidate> result = new ArrayList<>(values);
        if ("quality_source".equals(settings.sortMode)) {
            Collections.sort(result, (first, second) -> {
                int quality = Integer.compare(first.resolutionRank, second.resolutionRank);
                if (quality != 0) return quality;
                int source = Integer.compare(first.sourcePriority, second.sourcePriority);
                return source != 0 ? source : preferences.compare(first, second);
            });
        } else if ("source_priority".equals(settings.sortMode)) {
            Collections.sort(result, (first, second) -> {
                int source = Integer.compare(first.sourcePriority, second.sourcePriority);
                if (source != 0) return source;
                int quality = Integer.compare(first.resolutionRank, second.resolutionRank);
                return quality != 0 ? quality : preferences.compare(first, second);
            });
        } else {
            Collections.sort(result, (first, second) -> {
                int source = Integer.compare(first.sourcePriority, second.sourcePriority);
                return source != 0 ? source
                        : Integer.compare(first.sourceOrder, second.sourceOrder);
            });
        }
        return result;
    }

    private static List<Candidate> interleaveByQuality(
            List<Candidate> values, Comparator<Candidate> preferences) {
        List<Candidate> result = new ArrayList<>();
        for (String quality : QUALITY_ORDER) {
            Map<Integer, List<Candidate>> bySource = new LinkedHashMap<>();
            for (Candidate value : values) {
                if (!quality.equals(value.resolution)) {
                    continue;
                }
                List<Candidate> sourceValues = bySource.get(value.sourcePriority);
                if (sourceValues == null) {
                    sourceValues = new ArrayList<>();
                    bySource.put(value.sourcePriority, sourceValues);
                }
                sourceValues.add(value);
            }
            List<Integer> priorities = new ArrayList<>(bySource.keySet());
            Collections.sort(priorities);
            int longest = 0;
            for (List<Candidate> sourceValues : bySource.values()) {
                Collections.sort(sourceValues, preferences);
                longest = Math.max(longest, sourceValues.size());
            }
            for (int round = 0; round < longest; round++) {
                for (Integer priority : priorities) {
                    List<Candidate> sourceValues = bySource.get(priority);
                    if (round < sourceValues.size()) {
                        result.add(sourceValues.get(round));
                    }
                }
            }
        }
        return result;
    }

    private static Comparator<Candidate> preferenceComparator(
            StremioAggregationPreferences.Snapshot settings) {
        return (first, second) -> {
            if (settings.preferCached && first.cached != second.cached) {
                return first.cached ? -1 : 1;
            }
            int language = Integer.compare(
                    languageRank(first.languages, settings.preferredLanguages),
                    languageRank(second.languages, settings.preferredLanguages));
            if (language != 0) {
                return language;
            }
            if (!"none".equals(settings.sizeSort)) {
                if ((first.sizeBytes <= 0L) != (second.sizeBytes <= 0L)) {
                    return first.sizeBytes <= 0L ? 1 : -1;
                }
                int size = Long.compare(first.sizeBytes, second.sizeBytes);
                if ("larger".equals(settings.sizeSort)) {
                    size = -size;
                }
                if (size != 0) {
                    return size;
                }
            }
            return Integer.compare(first.sourceOrder, second.sourceOrder);
        };
    }

    private static int languageRank(Set<String> languages, Set<String> preferences) {
        if (preferences.isEmpty()) {
            return 0;
        }
        int rank = 0;
        for (String preferred : preferences) {
            if (languages.contains(preferred)) {
                return rank;
            }
            rank++;
        }
        return preferences.size() + (languages.isEmpty() ? 1 : 0);
    }

    private static JSONObject format(
            Candidate value,
            StremioAggregationPreferences.Snapshot settings) throws JSONException {
        String sourceName = value.source.name.isEmpty()
                ? "Addon " + (value.sourcePriority + 1) : value.source.name;
        String quality = value.resolutionLabel();
        value.stream.put("name", settings.displayFields.contains("resolution")
                ? quality + " • " + sourceName : sourceName);

        List<String> technical = new ArrayList<>();
        if (settings.displayFields.contains("language") && !value.languages.isEmpty()) {
            technical.add(join(value.languages, "/").toUpperCase(Locale.ROOT));
        }
        if (settings.displayFields.contains("codec") && !value.codec.isEmpty()) {
            technical.add(value.codecLabel());
        }
        if (settings.displayFields.contains("hdr") && !value.hdr.isEmpty()
                && !"sdr".equals(value.hdr)) {
            technical.add("dv".equals(value.hdr) ? "Dolby Vision" : "HDR");
        }
        if (settings.displayFields.contains("audio")) {
            technical.addAll(value.audio);
        }
        if (settings.displayFields.contains("size") && value.sizeBytes > 0L) {
            technical.add(formatSize(value.sizeBytes));
        }
        if (settings.displayFields.contains("cached") && value.cached) {
            technical.add("Cached");
        }

        LinkedHashSet<String> lines = new LinkedHashSet<>();
        if (!technical.isEmpty()) {
            lines.add(join(technical, " • "));
        }
        if (settings.displayFields.contains("filename") && !value.filename.isEmpty()) {
            lines.add(value.filename);
        }
        if (settings.displayFields.contains("original")) {
            if (!value.originalName.isEmpty()
                    && !value.originalName.equalsIgnoreCase(sourceName)
                    && !value.originalName.equalsIgnoreCase(quality)) {
                lines.add(value.originalName);
            }
            if (!value.originalDescription.isEmpty()) {
                lines.add(value.originalDescription);
            }
        }
        value.stream.put("title", join(lines, "\n"));
        value.stream.remove("description");

        if (!"none".equals(settings.bingeGroup)) {
            JSONObject behaviorHints = value.stream.optJSONObject("behaviorHints");
            if (behaviorHints == null) {
                behaviorHints = new JSONObject();
                value.stream.put("behaviorHints", behaviorHints);
            }
            String group;
            if ("source".equals(settings.bingeGroup)) {
                group = "jpp:v1:s:" + value.source.id;
            } else if ("source_quality".equals(settings.bingeGroup)) {
                group = "jpp:v1:sq:" + value.source.id + ':' + value.resolution;
            } else if ("any".equals(settings.bingeGroup)) {
                group = "jpp:v1:any";
            } else {
                group = "jpp:v1:q:" + value.resolution;
            }
            behaviorHints.put("bingeGroup", group);
        }
        return value.stream;
    }

    static String normalizeLanguage(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String alias = LANGUAGE_ALIASES.get(normalized);
        return alias == null ? normalized : alias;
    }

    private static Set<String> detectLanguages(String text) {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        for (Map.Entry<String, String> alias : LANGUAGE_ALIASES.entrySet()) {
            String token = alias.getKey();
            if (token.length() < 2) {
                continue;
            }
            Pattern pattern = Pattern.compile(
                    "(?i)(?:^|[\\s._+|/\\-\\[\\]()])" + Pattern.quote(token)
                            + "(?:$|[\\s._+|/\\-\\[\\]()])");
            if (pattern.matcher(text).find()) {
                languages.add(alias.getValue());
            }
        }
        return languages;
    }

    private static Set<String> detectReleaseTypes(String text) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (releasePattern("cam|camrip|hdcam").matcher(text).find()) types.add("cam");
        if (releasePattern("ts|telesync|hdts").matcher(text).find()) types.add("ts");
        if (releasePattern("tc|telecine|hdtc").matcher(text).find()) types.add("tc");
        if (releasePattern("scr|screener|dvdscr").matcher(text).find()) types.add("scr");
        if (releasePattern("3d|sbs|tab").matcher(text).find()) types.add("3d");
        return types;
    }

    private static Pattern releasePattern(String alternatives) {
        return Pattern.compile("(?i)(?:^|[^a-z0-9])(?:" + alternatives
                + ")(?:$|[^a-z0-9])");
    }

    private static List<String> detectAudio(String text) {
        LinkedHashSet<String> audio = new LinkedHashSet<>();
        if (Pattern.compile("(?i)(?:atmos)").matcher(text).find()) audio.add("Atmos");
        if (Pattern.compile("(?i)(?:dts[ ._-]?x)").matcher(text).find()) audio.add("DTS:X");
        if (Pattern.compile("(?i)(?:true[ ._-]?hd)").matcher(text).find()) audio.add("TrueHD");
        if (Pattern.compile("(?i)(?:dts[ ._-]?hd)").matcher(text).find()) audio.add("DTS-HD");
        if (Pattern.compile("(?i)(?:ddp|eac3|e-ac-3)").matcher(text).find()) audio.add("E-AC-3");
        else if (Pattern.compile("(?i)(?:^|[^a-z0-9])(?:dd|ac3|ac-3)(?:$|[^a-z0-9])")
                .matcher(text).find()) audio.add("AC-3");
        if (Pattern.compile("(?i)(?:^|[^a-z0-9])aac(?:$|[^a-z0-9])")
                .matcher(text).find()) audio.add("AAC");
        Matcher channels = Pattern.compile("(?:^|[^0-9])(7\\.1|5\\.1|2\\.0)(?:$|[^0-9])")
                .matcher(text);
        if (channels.find()) audio.add(channels.group(1));
        return new ArrayList<>(audio);
    }

    private static long detectSize(JSONObject stream, String text) {
        JSONObject hints = stream.optJSONObject("behaviorHints");
        long numeric = positiveLong(stream.opt("videoSize"));
        if (numeric <= 0L && hints != null) {
            numeric = positiveLong(hints.opt("videoSize"));
        }
        if (numeric > 0L) {
            return numeric;
        }
        Matcher matcher = SIZE.matcher(text);
        if (!matcher.find()) {
            return 0L;
        }
        try {
            double amount = Double.parseDouble(matcher.group(1).replace(',', '.'));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            double multiplier;
            if ("tb".equals(unit)) multiplier = 1_000_000_000_000d;
            else if ("tib".equals(unit)) multiplier = 1_099_511_627_776d;
            else if ("gb".equals(unit)) multiplier = 1_000_000_000d;
            else if ("gib".equals(unit)) multiplier = 1_073_741_824d;
            else if ("mb".equals(unit)) multiplier = 1_000_000d;
            else multiplier = 1_048_576d;
            double bytes = amount * multiplier;
            return !Double.isNaN(bytes) && !Double.isInfinite(bytes)
                    && bytes > 0d && bytes <= Long.MAX_VALUE
                    ? (long) bytes : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long positiveLong(Object value) {
        if (value instanceof Number) {
            long result = ((Number) value).longValue();
            return result > 0L ? result : 0L;
        }
        if (value instanceof String) {
            try {
                long result = Long.parseLong((String) value);
                return result > 0L ? result : 0L;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000d)
                    .replace(".0 GB", " GB");
        }
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000d);
    }

    private static String join(Iterable<String> values, String separator) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (output.length() > 0) output.append(separator);
            output.append(value.trim());
        }
        return output.toString();
    }

    private static Map<String, String> languageAliases() {
        Map<String, String> values = new LinkedHashMap<>();
        aliases(values, "cz", "cz", "cs", "cze", "ces", "czech");
        aliases(values, "sk", "sk", "svk", "slk", "slovak");
        aliases(values, "en", "en", "eng", "english");
        aliases(values, "de", "de", "deu", "ger", "german");
        aliases(values, "fr", "fr", "fra", "fre", "french");
        aliases(values, "es", "es", "spa", "spanish");
        aliases(values, "it", "it", "ita", "italian");
        aliases(values, "pl", "pl", "pol", "polish");
        aliases(values, "hu", "hu", "hun", "hungarian");
        aliases(values, "uk", "uk", "ukr", "ua", "ukrainian");
        aliases(values, "ru", "ru", "rus", "russian");
        aliases(values, "pt", "pt", "por", "portuguese");
        aliases(values, "nl", "nl", "nld", "dut", "dutch");
        aliases(values, "ja", "ja", "jpn", "japanese");
        aliases(values, "ko", "ko", "kor", "korean");
        aliases(values, "zh", "zh", "zho", "chi", "chinese");
        return Collections.unmodifiableMap(values);
    }

    private static void aliases(Map<String, String> target, String canonical, String... aliases) {
        for (String alias : aliases) target.put(alias, canonical);
    }

    private static final class Candidate {
        final JSONObject stream;
        final StremioStreamSourceStore.Source source;
        final int sourcePriority;
        final int sourceOrder;
        final String originalName;
        final String originalDescription;
        final String filename;
        final String searchText;
        final String resolution;
        final int resolutionRank;
        final String streamType;
        final String codec;
        final String hdr;
        final Set<String> languages;
        final Set<String> releaseTypes;
        final List<String> audio;
        final long sizeBytes;
        final boolean cached;

        private Candidate(JSONObject stream,
                          SourceStreams sourceResult,
                          int sourceOrder,
                          String originalName,
                          String originalDescription,
                          String filename,
                          String searchText,
                          String resolution,
                          String streamType,
                          String codec,
                          String hdr,
                          Set<String> languages,
                          Set<String> releaseTypes,
                          List<String> audio,
                          long sizeBytes,
                          boolean cached) {
            this.stream = stream;
            source = sourceResult.source;
            sourcePriority = sourceResult.priority;
            this.sourceOrder = sourceOrder;
            this.originalName = originalName;
            this.originalDescription = originalDescription;
            this.filename = filename;
            this.searchText = searchText;
            this.resolution = resolution;
            resolutionRank = QUALITY_ORDER.indexOf(resolution);
            this.streamType = streamType;
            this.codec = codec;
            this.hdr = hdr;
            this.languages = languages;
            this.releaseTypes = releaseTypes;
            this.audio = audio;
            this.sizeBytes = sizeBytes;
            this.cached = cached;
        }

        @Nullable
        static Candidate parse(JSONObject stream, SourceStreams sourceResult, int sourceOrder) {
            String streamType;
            if (!stream.optString("url", "").isEmpty()) streamType = "direct";
            else if (!stream.optString("infoHash", "").isEmpty()) streamType = "torrent";
            else if (!stream.optString("externalUrl", "").isEmpty()) streamType = "external";
            else return null;

            JSONObject hints = stream.optJSONObject("behaviorHints");
            String originalName = stream.optString("name", "").trim();
            String title = stream.optString("title", "").trim();
            String description = stream.optString("description", "").trim();
            String originalDescription = title.isEmpty() ? description
                    : description.isEmpty() || description.equals(title)
                    ? title : title + "\n" + description;
            String filename = hints == null ? "" : hints.optString("filename", "").trim();
            if (filename.isEmpty()) filename = stream.optString("filename", "").trim();
            String text = join(Arrays.asList(originalName, originalDescription, filename), "\n");
            String lower = text.toLowerCase(Locale.ROOT);
            String resolution = detectResolution(text);
            String codec = HEVC.matcher(text).find() ? "hevc"
                    : H264.matcher(text).find() ? "h264"
                    : AV1.matcher(text).find() ? "av1"
                    : OTHER_CODEC.matcher(text).find() ? "other" : "";
            String hdr = DOLBY_VISION.matcher(text).find() ? "dv"
                    : HDR.matcher(text).find() ? "hdr"
                    : SDR.matcher(text).find() ? "sdr" : "";
            boolean cached = !BAD_UNCACHED.matcher(text).find() && CACHED.matcher(text).find();
            return new Candidate(
                    stream, sourceResult, sourceOrder, originalName, originalDescription,
                    filename, lower, resolution, streamType, codec, hdr,
                    detectLanguages(text), detectReleaseTypes(text), detectAudio(text),
                    detectSize(stream, text), cached);
        }

        private static String detectResolution(String text) {
            if (RESOLUTION_2160.matcher(text).find()) return "2160p";
            if (RESOLUTION_1080.matcher(text).find()) return "1080p";
            if (RESOLUTION_720.matcher(text).find()) return "720p";
            if (RESOLUTION_SD.matcher(text).find()) return "sd";
            return "unknown";
        }

        String resolutionLabel() {
            if ("2160p".equals(resolution)) return "4K";
            if ("1080p".equals(resolution)) return "1080p";
            if ("720p".equals(resolution)) return "720p";
            if ("sd".equals(resolution)) return "SD";
            return "cs".equals(Locale.getDefault().getLanguage())
                    ? "Neznámá kvalita" : "Unknown quality";
        }

        String codecLabel() {
            if ("hevc".equals(codec)) return "HEVC";
            if ("h264".equals(codec)) return "H.264";
            if ("av1".equals(codec)) return "AV1";
            return "cs".equals(Locale.getDefault().getLanguage())
                    ? "Jiný kodek" : "Other codec";
        }

        List<String> deduplicationKeys(boolean extended) {
            List<String> keys = new ArrayList<>();
            String url = stream.optString("url", "");
            if (!url.isEmpty()) keys.add("url:" + url);
            String externalUrl = stream.optString("externalUrl", "");
            if (!externalUrl.isEmpty()) keys.add("external:" + externalUrl);
            String infoHash = stream.optString("infoHash", "").trim().toLowerCase(Locale.ROOT);
            if (!infoHash.isEmpty()) {
                keys.add("torrent:" + infoHash + ':' + stream.optInt("fileIdx", -1));
            }
            JSONObject hints = stream.optJSONObject("behaviorHints");
            String videoHash = stream.optString("videoHash", "");
            if (videoHash.isEmpty() && hints != null) {
                videoHash = hints.optString("videoHash", "");
            }
            long videoSize = positiveLong(stream.opt("videoSize"));
            if (videoSize <= 0L && hints != null) videoSize = positiveLong(hints.opt("videoSize"));
            if (!videoHash.isEmpty() && videoSize > 0L) {
                keys.add("video:" + videoHash.toLowerCase(Locale.ROOT) + ':' + videoSize);
            }
            if (extended && !filename.isEmpty() && sizeBytes > 0L) {
                keys.add("file:" + filename.toLowerCase(Locale.ROOT) + ':' + sizeBytes);
            }
            return keys;
        }
    }
}
