package com.brouken.player;

import static org.junit.Assert.assertEquals;
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

        StremioEpisodeId current = StremioConnectorStore.findRecentEpisode(
                events(
                        event("tt1:2:3", now - 2_000),
                        event("tt1:2:4", now - 1_000)),
                now);

        assertNotNull(current);
        assertEquals("tt1:2:4", current.raw);
        assertNull(StremioConnectorStore.findRecentEpisode(
                events(event("tt1:2:4", now - 16 * 60_000L)), now));
    }

    @Test
    public void metadataDerivesNextEpisodeFromCurrentOnly() throws JSONException {
        StremioEpisodeId current = StremioEpisodeId.parse("tt123:1:1");
        String json = "{\"meta\":{\"name\":\"Bluey\","
                + "\"background\":\"https://example.test/background.jpg\","
                + "\"videos\":["
                + "{\"id\":\"tt123:1:3\",\"title\":\"Later\","
                + "\"released\":\"2024-01-03T00:00:00.000Z\"},"
                + "{\"id\":\"tt123:1:1\",\"title\":\"Current\","
                + "\"released\":\"2024-01-01T00:00:00.000Z\"},"
                + "{\"id\":\"tt123:1:2\",\"name\":\"BBQ\","
                + "\"thumbnail\":\"https://example.test/episode.jpg\","
                + "\"released\":\"2024-01-02T00:00:00.000Z\"}]}}";

        NextEpisodeInfo info = NextEpisodeMetadataResolver.parse(
                current, json, 1_800_000_000_000L);

        assertNotNull(info);
        assertEquals("tt123:1:2", info.next.raw);
        assertEquals("BBQ", info.episodeTitle);
        assertEquals("https://example.test/episode.jpg", info.artworkUrl);
    }

    @Test
    public void metadataDoesNotSkipAnUnreleasedNextEpisode() throws JSONException {
        StremioEpisodeId current = StremioEpisodeId.parse("tt123:1:1");
        String json = "{\"meta\":{\"videos\":["
                + "{\"id\":\"tt123:1:2\",\"released\":\"2030-01-01\"},"
                + "{\"id\":\"tt123:1:3\",\"released\":\"2024-01-01\"}]}}";

        assertNull(NextEpisodeMetadataResolver.parse(
                current, json, 1_800_000_000_000L));
    }

    private static StremioConnectorStore.Event event(String id, long timestamp) {
        return new StremioConnectorStore.Event("series", id, timestamp);
    }

    private static List<StremioConnectorStore.Event> events(
            StremioConnectorStore.Event... events) {
        return Arrays.asList(events);
    }
}
