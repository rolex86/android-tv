package com.brouken.player.aisubtitles;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AiSubtitlePolicyTest {
    @Test
    public void supportsOnlyExternalFirstVersionTextMimeTypes() {
        assertTrue(SelectedSubtitleResolver.isSupportedMime("application/x-subrip"));
        assertTrue(SelectedSubtitleResolver.isSupportedMime("text/vtt"));
        assertTrue(SelectedSubtitleResolver.isSupportedMime("application/vtt"));
        assertFalse(SelectedSubtitleResolver.isSupportedMime("text/x-ssa"));
        assertFalse(SelectedSubtitleResolver.isSupportedMime("application/pgs"));
    }

    @Test
    public void localCacheFingerprintNormalizesLineEndingsAndIncludesLanguage() {
        String unix = AiSubtitleFileStore.sourceFingerprint("one\ntwo", "ces");
        String windows = AiSubtitleFileStore.sourceFingerprint("one\r\ntwo", "ces");
        assertEquals(unix, windows);
        assertNotEquals(unix, AiSubtitleFileStore.sourceFingerprint("one\ntwo", "eng"));
    }

    @Test
    public void readyBackendResponseIsStrictlyValidated() throws JSONException {
        JSONObject response = new JSONObject()
                .put("status", "ready")
                .put("cacheKey", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .put("outputFormat", "srt")
                .put("language", "ces")
                .put("label", "Čeština (AI)")
                .put("subtitleText", "1\n00:00:01,000 --> 00:00:02,000\nAhoj")
                .put("cached", true);
        assertNotNull(AiSubtitleBackendClient.parseReady(response, "ces"));
        assertNull(AiSubtitleBackendClient.parseReady(response, "eng"));
        response.put("outputFormat", "vtt");
        assertNull(AiSubtitleBackendClient.parseReady(response, "ces"));
    }

    @Test
    public void backendAddressAllowsOnlyCredentialFreeHttpOrHttps() {
        assertTrue(AiSubtitleBackendClient.isValidBackendUrl("http://192.168.1.10:8787"));
        assertTrue(AiSubtitleBackendClient.isValidBackendUrl("https://translate.example.test"));
        assertFalse(AiSubtitleBackendClient.isValidBackendUrl("ftp://translate.example.test"));
        assertFalse(AiSubtitleBackendClient.isValidBackendUrl("https://key@example.test"));
        assertFalse(AiSubtitleBackendClient.isValidBackendUrl("https://example.test?key=secret"));
    }

    @Test
    public void lateResultIsRejectedAfterDisableMovieChangeOrSourceChange() {
        assertTrue(current(true, false, 5, 5, "movie-a", "movie-a", "sub-a", "sub-a"));
        assertFalse(current(false, false, 5, 5, "movie-a", "movie-a", "sub-a", "sub-a"));
        assertFalse(current(true, false, 5, 6, "movie-a", "movie-a", "sub-a", "sub-a"));
        assertFalse(current(true, false, 5, 5, "movie-a", "movie-b", "sub-a", "sub-a"));
        assertFalse(current(true, false, 5, 5, "movie-a", "movie-a", "sub-a", "sub-b"));
    }

    private static boolean current(boolean enabled,
                                   boolean released,
                                   int expectedGeneration,
                                   int currentGeneration,
                                   String expectedMedia,
                                   String currentMedia,
                                   String expectedSource,
                                   String currentSource) {
        return AiSubtitleSessionGuard.isCurrent(
                enabled, released, 10L, 10L,
                expectedGeneration, currentGeneration,
                expectedMedia, currentMedia,
                expectedSource, currentSource,
                "ces", "ces");
    }
}
