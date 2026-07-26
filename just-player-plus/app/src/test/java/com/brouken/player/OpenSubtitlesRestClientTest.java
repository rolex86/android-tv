package com.brouken.player;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

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
