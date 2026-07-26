package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class StremioNextEpisodeTest {

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
    public void subtitleRequestSupportsQueryExtrasAndRejectsMissingIdentity() {
        StremioSubtitleRequest movie = StremioSubtitleRequest.parse(
                "/subtitles/movie/hash.json"
                        + "?videoID=tt0133093&filename=The+Matrix+1999.mkv");

        assertNotNull(movie);
        assertEquals("movie", movie.type);
        assertEquals("tt0133093", movie.videoId);
        assertEquals("The Matrix 1999.mkv", movie.filename);
        assertNull(StremioSubtitleRequest.parse(
                "/subtitles/movie/hash/filename=Movie.mkv.json"));
        assertNull(StremioSubtitleRequest.parse(
                "/subtitles/series/hash/videoID=tt0133093.json"));
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

    private static List<StremioConnectorStore.Event> events(
            StremioConnectorStore.Event... events) {
        return Arrays.asList(events);
    }
}
