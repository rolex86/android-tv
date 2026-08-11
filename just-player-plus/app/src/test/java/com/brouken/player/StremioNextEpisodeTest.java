package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class StremioNextEpisodeTest {

    @Test
    public void nextEpisodePopupUsesMediumAsDefault() {
        assertEquals(NextEpisodePopupSize.MEDIUM, NextEpisodePopupSize.DEFAULT);
        assertEquals(NextEpisodePopupSize.MEDIUM,
                NextEpisodePopupSize.fromPreference(null));
        assertEquals(NextEpisodePopupSize.MEDIUM,
                NextEpisodePopupSize.fromPreference("unknown"));
    }

    @Test
    public void nextEpisodePopupProfilesCoverTheWholeCard() {
        assertEquals(NextEpisodePopupSize.SMALL,
                NextEpisodePopupSize.fromPreference("small"));
        assertEquals(NextEpisodePopupSize.MEDIUM,
                NextEpisodePopupSize.fromPreference("medium"));
        assertEquals(NextEpisodePopupSize.LARGE,
                NextEpisodePopupSize.fromPreference("large"));

        assertTrue(NextEpisodePopupSize.SMALL.cardWidthDp
                < NextEpisodePopupSize.MEDIUM.cardWidthDp);
        assertTrue(NextEpisodePopupSize.MEDIUM.cardWidthDp
                < NextEpisodePopupSize.LARGE.cardWidthDp);
        assertTrue(NextEpisodePopupSize.SMALL.cardHeightDp
                < NextEpisodePopupSize.MEDIUM.cardHeightDp);
        assertTrue(NextEpisodePopupSize.MEDIUM.cardHeightDp
                < NextEpisodePopupSize.LARGE.cardHeightDp);
        assertTrue(NextEpisodePopupSize.SMALL.buttonHeightDp
                < NextEpisodePopupSize.MEDIUM.buttonHeightDp);
        assertTrue(NextEpisodePopupSize.MEDIUM.buttonHeightDp
                < NextEpisodePopupSize.LARGE.buttonHeightDp);
        assertEquals(460, NextEpisodePopupSize.LARGE.cardWidthDp);
        assertEquals(190, NextEpisodePopupSize.LARGE.cardHeightDp);
    }

    @Test
    public void nextEpisodeWatchdogPollsSparselyUntilPlaybackApproachesPopup() {
        long durationMs = 60 * 60_000L;
        long noticeMs = 30_000L;

        assertEquals(NextEpisodePopupWatchdog.FAR_INTERVAL_MS,
                NextEpisodePopupWatchdog.nextDelayMs(durationMs, 0L, noticeMs));
        assertEquals(NextEpisodePopupWatchdog.APPROACHING_INTERVAL_MS,
                NextEpisodePopupWatchdog.nextDelayMs(durationMs,
                        durationMs - noticeMs - 4 * 60_000L, noticeMs));
        assertEquals(NextEpisodePopupWatchdog.NEAR_INTERVAL_MS,
                NextEpisodePopupWatchdog.nextDelayMs(durationMs,
                        durationMs - noticeMs - 45_000L, noticeMs));
        assertEquals(0L, NextEpisodePopupWatchdog.nextDelayMs(
                durationMs, durationMs - noticeMs, noticeMs));
    }

    @Test
    public void nextEpisodeWatchdogRetriesWhenDurationIsNotKnownYet() {
        assertEquals(NextEpisodePopupWatchdog.RETRY_WITHOUT_DURATION_MS,
                NextEpisodePopupWatchdog.nextDelayMs(C.TIME_UNSET, 0L, 30_000L));
    }

    @Test
    public void nextEpisodeStreamPrefetchStartsThreeMinutesBeforeTheEnd() {
        long durationMs = 30 * 60_000L;

        assertEquals(durationMs - NextEpisodeStreamPrefetchPolicy.LEAD_MS,
                NextEpisodeStreamPrefetchPolicy.triggerPositionMs(durationMs));
        assertFalse(NextEpisodeStreamPrefetchPolicy.shouldStart(
                durationMs, durationMs - NextEpisodeStreamPrefetchPolicy.LEAD_MS - 1L));
        assertTrue(NextEpisodeStreamPrefetchPolicy.shouldStart(
                durationMs, durationMs - NextEpisodeStreamPrefetchPolicy.LEAD_MS));
    }

    @Test
    public void shortEpisodePrefetchesAsSoonAsItsMetadataIsReady() {
        long durationMs = 2 * 60_000L;

        assertEquals(0L, NextEpisodeStreamPrefetchPolicy.triggerPositionMs(durationMs));
        assertTrue(NextEpisodeStreamPrefetchPolicy.shouldStart(durationMs, 0L));
        assertEquals(-1L, NextEpisodeStreamPrefetchPolicy.triggerPositionMs(C.TIME_UNSET));
    }

    @Test
    public void episodeIdUsesLastTwoSegments() {
        StremioEpisodeId id = StremioEpisodeId.parse("custom:meta:2:7");

        assertNotNull(id);
        assertEquals("custom:meta", id.metaId);
        assertEquals(2, id.season);
        assertEquals(7, id.episode);
        assertEquals("S02E07", id.displayCode());
    }

    @Test
    public void episodeIdRejectsMalformedAndNegativeValues() {
        assertNull(StremioEpisodeId.parse(null));
        assertNull(StremioEpisodeId.parse("tt123"));
        assertNull(StremioEpisodeId.parse("tt123:x:2"));
        assertNull(StremioEpisodeId.parse("tt123:-1:2"));
    }

    @Test
    public void nextEpisodeDeepLinkTargetsTheResolvedVideoAndRequestsAutoplay() {
        assertEquals(
                "stremio:///detail/series/tt7678620/tt7678620:3:43?autoPlay=true",
                StremioNextEpisodeDeepLink.build(
                        StremioEpisodeId.parse("tt7678620:3:43")));
        assertEquals(
                "stremio:///detail/series/custom%2Fshow/custom%2Fshow:2:7?autoPlay=true",
                StremioNextEpisodeDeepLink.build(
                        StremioEpisodeId.parse("custom/show:2:7")));
        assertNull(StremioNextEpisodeDeepLink.build(null));
    }

    @Test
    public void naturalEndLaunchesOnlyWhenContinuationWasNotDismissed() {
        assertTrue(StremioNextEpisodeDeepLink.shouldLaunchAtNaturalEnd(
                true, true, false));
        assertFalse(StremioNextEpisodeDeepLink.shouldLaunchAtNaturalEnd(
                true, true, true));
        assertFalse(StremioNextEpisodeDeepLink.shouldLaunchAtNaturalEnd(
                true, false, false));
        assertFalse(StremioNextEpisodeDeepLink.shouldLaunchAtNaturalEnd(
                false, true, false));
    }

    @Test
    public void matcherUsesLatestEpisodeRequestAsCurrent() {
        long now = 1_000_000L;

        StremioConnectorStore.Content current = StremioConnectorStore.findRecentContent(
                events(
                        event("tt1:2:3", now - 2_000),
                        event("tt1:2:4", now - 1_000)),
                now);

        assertNotNull(current);
        assertNotNull(current.episode);
        assertEquals("tt1:2:4", current.episode.raw);
        assertNull(StremioConnectorStore.findRecentContent(
                events(event("tt1:2:4", now - 16 * 60_000L)), now));
    }

    @Test
    public void matcherRejectsBrowsingRequestsOlderThanCorrelationWindow() {
        long now = 1_000_000L;

        assertNotNull(StremioConnectorStore.findRecentContent(
                events(event("tt1:2:4", now - 899_000L)), now));
        assertNull(StremioConnectorStore.findRecentContent(
                events(event("tt1:2:4", now - 901_000L)), now));
    }

    @Test
    public void expectedNextNeverOverridesANewerStreamRequest() {
        StremioConnectorStore.ExpectedEpisode expected =
                new StremioConnectorStore.ExpectedEpisode(
                        StremioEpisodeId.parse("tt1:2:4"), 10_000L);

        assertTrue(StremioConnectorStore.shouldUseExpectedEpisode(
                event("tt1:2:3", 9_999L), expected));
        assertTrue(StremioConnectorStore.shouldUseExpectedEpisode(null, expected));
        assertFalse(StremioConnectorStore.shouldUseExpectedEpisode(
                event("tt1:2:3", 10_001L), expected));
        assertFalse(StremioConnectorStore.shouldUseExpectedEpisode(
                event("tt1:2:3", 10_000L), expected));
    }

    @Test
    public void freshMovieRequestSupersedesStaleSeriesRequest() {
        long now = 1_000_000L;

        StremioConnectorStore.Content current = StremioConnectorStore.findRecentContent(
                events(
                        event("tt1:2:3", now - 2_000),
                        new StremioConnectorStore.Event("movie", "tt999", now - 1_000)),
                now);

        assertNotNull(current);
        assertEquals("movie", current.type);
        assertEquals("tt999", current.id);
        assertNull(current.episode);
    }

    @Test
    public void launchIdentityIsStableAndDoesNotStoreTheRawTitle() {
        String first = StremioConnectorStore.hashIdentity(" file_a9bbacce74d64874 ");
        String second = StremioConnectorStore.hashIdentity("file_a9bbacce74d64874");

        assertNotNull(first);
        assertEquals(first, second);
        assertNotEquals("file_a9bbacce74d64874", first);
        assertNull(StremioConnectorStore.hashIdentity("   "));
    }

    @Test
    public void rememberedContentRejectsMalformedTypesAndIds() {
        StremioConnectorStore.Content movie =
                StremioConnectorStore.Content.fromValues("movie", "tt999");
        StremioConnectorStore.Content series =
                StremioConnectorStore.Content.fromValues("series", "tt123:2:4");

        assertNotNull(movie);
        assertEquals("tt999", movie.id);
        assertNotNull(series);
        assertNotNull(series.episode);
        assertEquals("tt123:2:4", series.episode.raw);
        assertNull(StremioConnectorStore.Content.fromValues("series", "tt123"));
        assertNull(StremioConnectorStore.Content.fromValues("channel", "tt999"));
    }

    @Test
    public void subtitleRequestRecoversEpisodeIdentityAndFilename() {
        StremioSubtitleRequest request = StremioSubtitleRequest.parse(
                "/subtitles/series/efe600f792bf6a7f/"
                        + "videoID=tt123%3A2%3A4&videoSize=123456"
                        + "&filename=Show.S02E04.1080p.WEB-DL.mkv.json");

        assertNotNull(request);
        assertEquals("series", request.type);
        assertEquals("tt123:2:4", request.videoId);
        assertEquals("Show.S02E04.1080p.WEB-DL.mkv", request.filename);
    }

    @Test
    public void subtitleFilenameFlowsFromConnectorEventIntoResolvedContent() {
        StremioConnectorStore.Content content =
                StremioConnectorStore.Content.fromEvent(
                        new StremioConnectorStore.Event(
                                "movie",
                                "tt0133093",
                                "The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv",
                                1_000L));

        assertNotNull(content);
        assertEquals(
                "The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv",
                content.mediaFilename);
        assertEquals(
                content.mediaFilename,
                content.withCorrelation("test", 50L).mediaFilename);
    }

    @Test
    public void lateSubtitleRequestRefreshesAlreadyResolvedContent() {
        StremioConnectorStore.Content original =
                StremioConnectorStore.Content.movie("tt0133093");
        List<StremioConnectorStore.Event> events = Arrays.asList(
                new StremioConnectorStore.Event("movie", "tt9999999", 1_500L),
                new StremioConnectorStore.Event(
                        "movie",
                        "tt0133093",
                        "The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv",
                        2_000L));

        StremioConnectorStore.Content refreshed =
                StremioConnectorStore.refreshContent(events, original, 2_500L);

        assertEquals(
                "The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv",
                refreshed.mediaFilename);
    }

    @Test
    public void filenameRefreshRejectsStaleEventsAndKeepsExistingIdentity() {
        StremioConnectorStore.Content unresolved =
                StremioConnectorStore.Content.movie("tt0133093");
        StremioConnectorStore.Content alreadyResolved = unresolved.withMediaFilename(
                "Current.Release.mkv");
        List<StremioConnectorStore.Event> events = Arrays.asList(
                new StremioConnectorStore.Event(
                        "movie", "tt0133093", "Stale.Release.mkv", 1_000L),
                new StremioConnectorStore.Event(
                        "movie", "tt0133093", "Different.Release.mkv", 10_000L));

        assertNull(StremioConnectorStore.refreshContent(
                events, unresolved, 10_000L + 5_001L).mediaFilename);
        assertEquals(
                "Current.Release.mkv",
                StremioConnectorStore.refreshContent(
                        events, alreadyResolved, 10_000L).mediaFilename);
    }

    @Test
    public void subtitleRequestSupportsCurrentAndLegacyIdentityFormats() {
        StremioSubtitleRequest currentMovie = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/"
                        + "videoHash=efe600f792bf6a7f&videoSize=123456"
                        + "&filename=The+Matrix+1999.mkv.json");
        StremioSubtitleRequest currentSeries = StremioSubtitleRequest.parse(
                "/subtitles/series/tt123%3A2%3A4/"
                        + "filename=Show.S02E04.1080p.WEB-DL.mkv.json");
        StremioSubtitleRequest legacyMovie = StremioSubtitleRequest.parse(
                "/subtitles/movie/hash.json"
                        + "?videoID=tt0133093&filename=The+Matrix+1999.mkv");

        assertNotNull(currentMovie);
        assertEquals("movie", currentMovie.type);
        assertEquals("tt0133093", currentMovie.videoId);
        assertEquals("The Matrix 1999.mkv", currentMovie.filename);
        assertNotNull(currentSeries);
        assertEquals("tt123:2:4", currentSeries.videoId);
        assertEquals("Show.S02E04.1080p.WEB-DL.mkv", currentSeries.filename);
        assertNotNull(legacyMovie);
        assertEquals("tt0133093", legacyMovie.videoId);
        assertNull(StremioSubtitleRequest.parse(
                "/subtitles/movie/hash/filename=Movie.mkv.json"));
        assertNull(StremioSubtitleRequest.parse(
                "/subtitles/series/hash/videoID=tt0133093.json"));
    }

    @Test
    public void identitySubtitleCarriesMovieAndFilenameThroughCachedResponse()
            throws JSONException {
        StremioSubtitleRequest request = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/"
                        + "filename=The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv.json");

        assertNotNull(request);
        JSONObject response = new JSONObject(StremioIdentitySubtitle.responseJson(request));
        JSONObject marker = response.getJSONArray("subtitles").getJSONObject(0);
        StremioIdentitySubtitle.Identity identity =
                StremioIdentitySubtitle.parse(marker.getString("url"));

        assertEquals("zxx", marker.getString("lang"));
        assertNotNull(identity);
        assertEquals("movie", identity.type);
        assertEquals("tt0133093", identity.videoId);
        assertEquals(
                "The.Matrix.1999.2160p.BluRay.x265-GROUP.mkv",
                identity.filename);
    }

    @Test
    public void identitySubtitleCarriesSeriesAndRejectsForeignUrls() {
        String marker = StremioIdentitySubtitle.buildUrl(
                "series", "tt3107288:1:2", null);
        StremioIdentitySubtitle.Identity identity = StremioIdentitySubtitle.parse(marker);

        assertNotNull(identity);
        assertEquals("series", identity.type);
        assertEquals("tt3107288:1:2", identity.videoId);
        assertNull(identity.filename);
        assertTrue(StremioIdentitySubtitle.isMarkerPath(
                "/identity/v1/series/tt3107288%3A1%3A2.vtt"));
        assertFalse(StremioIdentitySubtitle.isMarkerPath(
                "/identity/v1/movie/not-an-imdb-id.vtt"));
        assertNull(StremioIdentitySubtitle.parse(marker.replace(
                "127.0.0.1:16745", "example.com:16745")));
        assertNull(StremioIdentitySubtitle.parse(marker.replace(
                "127.0.0.1:16745", "127.0.0.1:16746")));
        assertNull(StremioIdentitySubtitle.parse(marker.replace("http://", "https://")));
    }

    @Test
    public void preloadedOpenSubtitlesRoundTripWithIdentityMarker() throws Exception {
        StremioSubtitleRequest request = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/filename=The.Matrix.1999.mkv.json");
        OpenSubtitlesV3Client.Candidate candidate = new OpenSubtitlesV3Client.Candidate(
                "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/42",
                "42",
                "ces",
                "OpenSubtitles v3 · CES · The.Matrix.1999",
                MimeTypes.APPLICATION_SUBRIP,
                C.ROLE_FLAG_SUBTITLE,
                0,
                OpenSubtitlesV3Client.MatchConfidence.UNKNOWN,
                0,
                0);

        JSONObject response = new JSONObject(StremioIdentitySubtitle.responseJson(
                request, java.util.Collections.singletonList(candidate)));
        JSONArray subtitles = response.getJSONArray("subtitles");
        StremioPreloadedSubtitle.Parsed preloaded = StremioPreloadedSubtitle.parse(
                subtitles.getJSONObject(0).getString("url"));
        StremioIdentitySubtitle.Identity marker = StremioIdentitySubtitle.parse(
                subtitles.getJSONObject(1).getString("url"));

        assertEquals(2, subtitles.length());
        assertNotNull(preloaded);
        assertEquals(candidate.url, preloaded.sourceUrl);
        assertEquals("ces", preloaded.language);
        assertEquals(candidate.label, preloaded.label);
        assertNotNull(marker);
        assertEquals("tt0133093", marker.videoId);
    }

    @Test
    public void preloadedOpenSubtitlesRejectForeignLoopbackAndSourceHosts() {
        OpenSubtitlesV3Client.Candidate candidate = new OpenSubtitlesV3Client.Candidate(
                "https://subs5.strem.io/en/download/file/42",
                "42",
                "ces",
                "OpenSubtitles v3 · CES · 42",
                MimeTypes.APPLICATION_SUBRIP,
                C.ROLE_FLAG_SUBTITLE,
                0,
                OpenSubtitlesV3Client.MatchConfidence.UNKNOWN,
                0,
                0);
        String url = StremioPreloadedSubtitle.buildUrl(candidate);

        assertNotNull(StremioPreloadedSubtitle.parse(url));
        assertTrue(StremioPreloadedSubtitle.isPath(
                "/opensubtitles/v1/ces/42.srt"));
        assertNull(StremioPreloadedSubtitle.parse(url.replace(
                "127.0.0.1:16745", "example.com:16745")));
        assertNull(StremioPreloadedSubtitle.parse(url.replace(
                "127.0.0.1:16745", "127.0.0.1:16746")));
        assertNull(StremioPreloadedSubtitle.parse(url.replace(
                "subs5.strem.io", "example.test")));
    }

    @Test
    public void localPreloadCacheSurvivesMissingExternalPlayerSubtitleExtras() {
        long now = 1_000_000L;
        StremioSubtitleRequest request = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/filename=The.Matrix.1999.mkv.json");
        OpenSubtitlesV3Client.Candidate candidate = candidate(
                "42", "ces", "The.Matrix.1999");

        assertNotNull(request);
        String encoded = StremioSubtitlePreloadCache.update(
                null,
                request,
                new String[]{"cs", "sk"},
                java.util.Collections.singletonList(candidate),
                now);
        StremioSubtitlePreloadCache.Lookup lookup = StremioSubtitlePreloadCache.find(
                encoded,
                StremioConnectorStore.Content.movie("tt0133093")
                        .withMediaFilename("The.Matrix.1999.mkv"),
                new String[]{"ces", "slk"},
                now + 500L);

        assertNotNull(lookup);
        assertEquals("exact_filename", lookup.match);
        assertEquals(500L, lookup.ageMs);
        assertEquals(1, lookup.tracks.size());
        assertEquals(candidate.url, lookup.tracks.get(0).sourceUrl);
    }

    @Test
    public void localPreloadCachePrefersExactReleaseOverNewerContentFallback() {
        long now = 2_000_000L;
        StremioSubtitleRequest firstRequest = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/filename=Release.A.mkv.json");
        StremioSubtitleRequest secondRequest = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/filename=Release.B.mkv.json");
        assertNotNull(firstRequest);
        assertNotNull(secondRequest);

        String encoded = StremioSubtitlePreloadCache.update(
                null,
                firstRequest,
                new String[]{"cs"},
                java.util.Collections.singletonList(candidate("100", "ces", "Release.A")),
                now);
        encoded = StremioSubtitlePreloadCache.update(
                encoded,
                secondRequest,
                new String[]{"cs"},
                java.util.Collections.singletonList(candidate("200", "ces", "Release.B")),
                now + 1_000L);

        StremioSubtitlePreloadCache.Lookup exact = StremioSubtitlePreloadCache.find(
                encoded,
                StremioConnectorStore.Content.movie("tt0133093")
                        .withMediaFilename("release.a.mkv"),
                new String[]{"ces"},
                now + 2_000L);

        assertNotNull(exact);
        assertEquals("exact_filename", exact.match);
        assertTrue(exact.tracks.get(0).sourceUrl.endsWith("/100"));
    }

    @Test
    public void localPreloadCacheRejectsExpiredOrDifferentLanguageOrder() {
        long now = 3_000_000L;
        StremioSubtitleRequest request = StremioSubtitleRequest.parse(
                "/subtitles/movie/tt0133093/filename=The.Matrix.1999.mkv.json");
        assertNotNull(request);
        String encoded = StremioSubtitlePreloadCache.update(
                null,
                request,
                new String[]{"cs", "sk"},
                java.util.Collections.singletonList(candidate("42", "ces", "The.Matrix")),
                now);
        StremioConnectorStore.Content content = StremioConnectorStore.Content.movie(
                "tt0133093");

        assertNull(StremioSubtitlePreloadCache.find(
                encoded, content, new String[]{"en"}, now + 1_000L));
        assertNull(StremioSubtitlePreloadCache.find(
                encoded,
                content,
                new String[]{"cs", "sk"},
                now + StremioSubtitlePreloadCache.MAX_AGE_MS + 1L));
    }

    @Test
    public void metadataDerivesNextEpisodeFromCurrentOnly() throws JSONException {
        StremioEpisodeId current = StremioEpisodeId.parse("tt123:1:1");
        String json = "{\"meta\":{\"name\":\"Bluey\","
                + "\"background\":\"https://example.test/background.jpg\","
                + "\"videos\":["
                + "{\"id\":\"tt123:1:3\",\"title\":\"Later\","
                + "\"released\":\"2024-01-03T00:00:00.000Z\"},"
                + "{\"id\":\"tt123:1:1\",\"name\":\"Current\","
                + "\"released\":\"2024-01-01T00:00:00.000Z\"},"
                + "{\"id\":\"tt123:1:2\",\"name\":\"BBQ\","
                + "\"thumbnail\":\"https://example.test/episode.jpg\","
                + "\"released\":\"2024-01-02T00:00:00.000Z\"}]}}";

        NextEpisodeMetadataResolver.Result result = NextEpisodeMetadataResolver.parse(
                current, json, 1_800_000_000_000L);

        assertNotNull(result);
        NextEpisodeInfo info = result.nextEpisode;
        assertNotNull(info);
        assertEquals("tt123:1:2", info.next.raw);
        assertEquals("Current", result.currentEpisodeTitle);
        assertEquals("BBQ", info.episodeTitle);
        assertEquals("https://example.test/episode.jpg", info.artworkUrl);
    }

    @Test
    public void metadataDoesNotSkipAnUnreleasedNextEpisode() throws JSONException {
        StremioEpisodeId current = StremioEpisodeId.parse("tt123:1:1");
        String json = "{\"meta\":{\"videos\":["
                + "{\"id\":\"tt123:1:1\",\"name\":\"Current\"},"
                + "{\"id\":\"tt123:1:2\",\"released\":\"2030-01-01\"},"
                + "{\"id\":\"tt123:1:3\",\"released\":\"2024-01-01\"}]}}";

        NextEpisodeMetadataResolver.Result result = NextEpisodeMetadataResolver.parse(
                current, json, 1_800_000_000_000L);

        assertNotNull(result);
        assertEquals("Current", result.currentEpisodeTitle);
        assertNull(result.nextEpisode);
    }

    @Test
    public void movieMetadataUsesCinemetaName() throws JSONException {
        String title = NextEpisodeMetadataResolver.parseMovieTitle(
                "{\"meta\":{\"id\":\"tt123\",\"name\":\"The Matrix\"}}");

        assertEquals("The Matrix", title);
    }

    @Test
    public void movieMetadataFallsBackToTitleAndRejectsMissingMeta() throws JSONException {
        assertEquals("Fallback title", NextEpisodeMetadataResolver.parseMovieTitle(
                "{\"meta\":{\"title\":\"Fallback title\"}}"));
        assertNull(NextEpisodeMetadataResolver.parseMovieTitle("{}"));
    }

    private static StremioConnectorStore.Event event(String id, long timestamp) {
        return new StremioConnectorStore.Event("series", id, timestamp);
    }

    private static OpenSubtitlesV3Client.Candidate candidate(
            String id, String language, String release) {
        return new OpenSubtitlesV3Client.Candidate(
                "https://subs5.strem.io/en/download/file/" + id,
                id,
                language,
                "OpenSubtitles v3 · " + language.toUpperCase() + " · " + release,
                MimeTypes.APPLICATION_SUBRIP,
                C.ROLE_FLAG_SUBTITLE,
                0,
                OpenSubtitlesV3Client.MatchConfidence.UNKNOWN,
                0,
                0);
    }

    private static List<StremioConnectorStore.Event> events(
            StremioConnectorStore.Event... events) {
        return Arrays.asList(events);
    }
}
