package com.brouken.player;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenSubtitlesV3ClientTest {
    @Test
    public void acceptsOnlyImdbMovieAndEpisodeIds() {
        assertTrue(OpenSubtitlesV3Client.isSupportedContent("movie", "tt33311069"));
        assertTrue(OpenSubtitlesV3Client.isSupportedContent("series", "tt3107288:1:2"));
        assertFalse(OpenSubtitlesV3Client.isSupportedContent("movie", "kitsu:123"));
        assertFalse(OpenSubtitlesV3Client.isSupportedContent("other", "tt33311069"));
    }

    @Test
    public void normalizesStremioAndIsoLanguageVariants() {
        assertEquals("ces", OpenSubtitlesV3Client.normalizeLanguage("cs"));
        assertEquals("ces", OpenSubtitlesV3Client.normalizeLanguage("cze"));
        assertEquals("slk", OpenSubtitlesV3Client.normalizeLanguage("slo"));
        assertEquals("eng", OpenSubtitlesV3Client.normalizeLanguage("en-US"));
        assertEquals("deu", OpenSubtitlesV3Client.normalizeLanguage("ger"));
    }

    @Test
    public void filtersDeduplicatesAndCapsPreferredLanguages() throws Exception {
        JSONArray subtitles = new JSONArray();
        for (int index = 0; index < 8; index++) {
            subtitles.put(item("cs", "https://example.test/cs-" + index + ".srt", "cs-" + index));
        }
        for (int index = 0; index < 7; index++) {
            subtitles.put(item("en", "https://example.test/en-" + index + ".srt", "en-" + index));
        }
        for (int index = 0; index < 5; index++) {
            subtitles.put(item("sk", "https://example.test/sk-" + index + ".srt", "sk-" + index));
        }
        subtitles.put(item("de", "https://example.test/de.srt", "de"));
        subtitles.put(item("cs", "ftp://example.test/bad.srt", "bad"));
        subtitles.put(item("cs", "https://example.test/cs-0.srt", "duplicate"));

        List<OpenSubtitlesV3Client.Candidate> result =
                OpenSubtitlesV3Client.parseCandidates(
                        new JSONObject().put("subtitles", subtitles).toString(),
                        new String[]{"ces", "eng", "slk"});

        assertEquals(13, result.size());
        assertEquals("ces", result.get(0).language);
        assertEquals("eng", result.get(5).language);
        assertEquals("slk", result.get(10).language);
    }

    @Test
    public void preservesForcedAndSdhHintsForSmartSelection() throws Exception {
        JSONArray subtitles = new JSONArray()
                .put(item("en", "https://example.test/forced.srt", "Movie.Forced"))
                .put(item("en", "https://example.test/sdh.srt", "Movie.SDH"));
        List<OpenSubtitlesV3Client.Candidate> result =
                OpenSubtitlesV3Client.parseCandidates(
                        new JSONObject().put("subtitles", subtitles).toString(),
                        new String[]{"eng"});

        assertEquals(2, result.size());
        assertTrue(result.get(0).selectionFlags != 0);
        assertTrue(result.get(1).roleFlags != result.get(0).roleFlags);
    }

    @Test
    public void recognizesLikelyReleaseNamesButRejectsConflictingResolution() {
        assertTrue(OpenSubtitlesV3Client.isLikelyReleaseMatch(
                "Movie.2026.2160p.UHD.BluRay.REMUX.HEVC-GROUP.mkv",
                "Movie.2026.2160p.BluRay.REMUX.HEVC-GROUP.srt"));
        assertFalse(OpenSubtitlesV3Client.isLikelyReleaseMatch(
                "Movie.2026.2160p.UHD.BluRay.REMUX.HEVC-GROUP.mkv",
                "Movie.2026.1080p.WEB-DL.x264-OTHER.srt"));
    }

    @Test
    public void refinementPromotesExistingMatchesAndKeepsExactOnlyResults() {
        OpenSubtitlesV3Client.Candidate genericUnknown = candidate(
                "https://example.test/shared.srt",
                OpenSubtitlesV3Client.MatchConfidence.UNKNOWN,
                0);
        OpenSubtitlesV3Client.Candidate genericLikely = candidate(
                "https://example.test/likely.srt",
                OpenSubtitlesV3Client.MatchConfidence.LIKELY,
                1);
        OpenSubtitlesV3Client.Candidate exactShared = candidate(
                genericUnknown.url,
                OpenSubtitlesV3Client.MatchConfidence.EXACT,
                0);
        OpenSubtitlesV3Client.Candidate exactOnly = candidate(
                "https://example.test/exact-only.srt",
                OpenSubtitlesV3Client.MatchConfidence.EXACT,
                1);

        List<OpenSubtitlesV3Client.Candidate> refined =
                OpenSubtitlesV3Client.mergeRefinedCandidates(
                        Arrays.asList(genericUnknown, genericLikely),
                        Arrays.asList(exactShared, exactOnly));

        assertEquals(3, refined.size());
        assertEquals(OpenSubtitlesV3Client.MatchConfidence.EXACT, refined.get(0).confidence);
        assertEquals(OpenSubtitlesV3Client.MatchConfidence.EXACT, refined.get(1).confidence);
        assertEquals(OpenSubtitlesV3Client.MatchConfidence.LIKELY, refined.get(2).confidence);
        boolean exactOnlyIncluded = false;
        for (OpenSubtitlesV3Client.Candidate candidate : refined) {
            if (exactOnly.url.equals(candidate.url)) {
                exactOnlyIncluded = true;
                break;
            }
        }
        assertTrue(exactOnlyIncluded);
    }

    private static OpenSubtitlesV3Client.Candidate candidate(
            String url,
            OpenSubtitlesV3Client.MatchConfidence confidence,
            int sourceOrder) {
        return new OpenSubtitlesV3Client.Candidate(
                url, "ces", "Czech", "application/x-subrip",
                0, 0, confidence, 0, sourceOrder);
    }

    private static JSONObject item(String language, String url, String id) throws Exception {
        return new JSONObject().put("lang", language).put("url", url).put("id", id);
    }
}
