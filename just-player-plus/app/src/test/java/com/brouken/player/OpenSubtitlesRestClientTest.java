package com.brouken.player;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import okhttp3.Request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenSubtitlesRestClientTest {
    @Test
    public void acceptsOnlyExplicitMovieHashMatchesInPreferredLanguages() throws Exception {
        JSONArray data = new JSONArray()
                .put(result("1", "cs", true, "", 101, 201, "Movie.CZ.srt"))
                .put(result("2", "en", false, "feature", 102, 202, "Movie.EN.srt"))
                .put(result("3", "sk", false, "moviehash", 103, 203, "Movie.SK.srt"))
                .put(result("4", "de", true, "", 104, 204, "Movie.DE.srt"));

        List<OpenSubtitlesRestClient.Candidate> candidates =
                OpenSubtitlesRestClient.parseExactCandidates(
                        new JSONObject().put("data", data).toString(),
                        new String[]{"ces", "slk", "eng"});

        assertEquals(2, candidates.size());
        assertEquals("ces", candidates.get(0).language);
        assertEquals("201", candidates.get(0).fileId);
        assertTrue(candidates.get(0).identifiers.contains("101"));
        assertEquals("slk", candidates.get(1).language);
    }

    @Test
    public void prefersNormalTrustedPopularResultWithinLanguage() throws Exception {
        JSONObject normal = result(
                "10", "cs", true, "", 110, 210, "Normal.srt");
        normal.getJSONObject("attributes")
                .put("from_trusted", true)
                .put("download_count", 500);
        JSONObject hearingImpaired = result(
                "11", "cs", true, "", 111, 211, "SDH.srt");
        hearingImpaired.getJSONObject("attributes")
                .put("hearing_impaired", true)
                .put("from_trusted", true)
                .put("download_count", 5_000);

        List<OpenSubtitlesRestClient.Candidate> candidates =
                OpenSubtitlesRestClient.parseExactCandidates(
                        new JSONObject().put("data", new JSONArray()
                                .put(hearingImpaired)
                                .put(normal)).toString(),
                        new String[]{"ces"});

        assertEquals("210", candidates.get(0).fileId);
        assertFalse(candidates.get(0).hearingImpaired);
    }

    @Test
    public void credentialsRequireApiKeyAndCompleteOptionalAccount() {
        assertTrue(new OpenSubtitlesCredentialsStore.Credentials(
                "api-key", "", "").isValid());
        assertTrue(new OpenSubtitlesCredentialsStore.Credentials(
                "api-key", "user", "password").isValid());
        assertFalse(new OpenSubtitlesCredentialsStore.Credentials(
                "", "user", "password").isValid());
        assertFalse(new OpenSubtitlesCredentialsStore.Credentials(
                "api-key", "user", "").isValid());
    }

    @Test
    public void buildsCanonicalApiUrlsWithoutRedirects() {
        Request hashRequest = OpenSubtitlesRestClient.searchRequest(
                "secret", "efe600f792bf6a7f", 1_000_000L,
                new String[]{"eng", "ces"},
                "movie",
                "tt0133093",
                "The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv");
        assertEquals(
                "imdb_id=133093&languages=cs%2Cen&moviebytesize=1000000"
                        + "&moviehash=efe600f792bf6a7f&moviehash_match=include"
                        + "&query=the+matrix+1999+2160p+bluray+x265+group&type=movie",
                hashRequest.url().encodedQuery());

        Request episodeRequest = OpenSubtitlesRestClient.searchRequest(
                "secret", "0123456789abcdef", 2_000_000L,
                new String[]{"ces"},
                "series",
                "tt3107288:1:2",
                "Show.S01E02.1080p.WEB-DL.x264-GROUP.mkv");
        assertEquals(
                "episode_number=2&languages=cs&moviebytesize=2000000"
                        + "&moviehash=0123456789abcdef&moviehash_match=include"
                        + "&parent_imdb_id=3107288"
                        + "&query=show+s01e02+1080p+web+dl+x264+group"
                        + "&season_number=1&type=episode",
                episodeRequest.url().encodedQuery());

        Request testRequest =
                OpenSubtitlesRestClient.credentialsTestRequest("secret");
        assertEquals(
                "languages=en&query=the+matrix",
                testRequest.url().encodedQuery());
    }

    @Test
    public void acceptsOnlyConservativeReleaseMatchesOutsideExactHashResults()
            throws Exception {
        JSONObject likely = result(
                "20", "cs", false, "feature", 120, 220,
                "The.Matrix.1999.2160p.BluRay.REMUX.HEVC-GROUP.srt");
        likely.getJSONObject("attributes").put(
                "release", "The.Matrix.1999.2160p.BluRay.REMUX.HEVC-GROUP");
        JSONObject titleOnly = result(
                "21", "cs", false, "feature", 121, 221,
                "The.Matrix.1999.srt");
        titleOnly.getJSONObject("attributes").put(
                "release", "The.Matrix.1999");
        JSONObject conflicting = result(
                "22", "cs", false, "feature", 122, 222,
                "The.Matrix.1999.1080p.WEB-DL.x264-OTHER.srt");
        conflicting.getJSONObject("attributes").put(
                "release", "The.Matrix.1999.1080p.WEB-DL.x264-OTHER");
        JSONObject exact = result(
                "23", "cs", true, "moviehash", 123, 223,
                "Different.Release.srt");

        List<OpenSubtitlesRestClient.Candidate> candidates =
                OpenSubtitlesRestClient.parseLikelyCandidates(
                        new JSONObject().put("data", new JSONArray()
                                .put(titleOnly)
                                .put(conflicting)
                                .put(exact)
                                .put(likely)).toString(),
                        new String[]{"ces"},
                        "The.Matrix.1999.2160p.BluRay.REMUX.HEVC-GROUP.mkv");

        assertEquals(1, candidates.size());
        assertEquals("220", candidates.get(0).fileId);
        assertTrue(candidates.get(0).releaseScore >= 55);
    }

    @Test
    public void syntheticFilenameCannotCreateProbableMatches() throws Exception {
        JSONObject result = result(
                "30", "cs", false, "feature", 130, 230,
                "file_a9bbacce74d64874.srt");
        assertTrue(OpenSubtitlesRestClient.parseLikelyCandidates(
                new JSONObject().put("data", new JSONArray().put(result)).toString(),
                new String[]{"ces"},
                "file_a9bbacce74d64874").isEmpty());
    }

    private static JSONObject result(String id,
                                     String language,
                                     boolean hashMatch,
                                     String matchedBy,
                                     int legacySubtitleId,
                                     int fileId,
                                     String filename) throws Exception {
        JSONObject attributes = new JSONObject()
                .put("language", language)
                .put("moviehash_match", hashMatch)
                .put("matched_by", matchedBy)
                .put("subtitle_id", id)
                .put("legacy_subtitle_id", legacySubtitleId)
                .put("release", "Movie.Release")
                .put("files", new JSONArray().put(new JSONObject()
                        .put("file_id", fileId)
                        .put("file_name", filename)));
        return new JSONObject().put("id", id).put("attributes", attributes);
    }
}
