package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
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
    public void matcherFindsAscendingEpisodeAndSeasonPairs() {
        long now = 1_000_000L;

        StremioConnectorStore.Pair sameSeason = StremioConnectorStore.findRecentPair(
                events(event("tt1:1:4", now - 2_000), event("tt1:1:5", now - 1_000)),
                now);
        StremioConnectorStore.Pair nextSeason = StremioConnectorStore.findRecentPair(
                events(event("tt1:1:10", now - 2_000), event("tt1:2:1", now - 1_000)),
                now);

        assertNotNull(sameSeason);
        assertEquals("tt1:1:5", sameSeason.next.raw);
        assertNotNull(nextSeason);
        assertEquals("tt1:2:1", nextSeason.next.raw);
    }

    @Test
    public void matcherIgnoresLoneReverseOtherSeriesAndExpiredRequests() {
        long now = 20 * 60_000L;

        assertNull(StremioConnectorStore.findRecentPair(
                Collections.singletonList(event("tt1:1:10", now - 1_000)), now));
        assertNull(StremioConnectorStore.findRecentPair(
                events(event("tt1:1:10", now - 2_000), event("tt1:1:9", now - 1_000)),
                now));
        assertNull(StremioConnectorStore.findRecentPair(
                events(event("tt1:1:1", now - 2_000), event("tt2:1:2", now - 1_000)),
                now));
        assertNull(StremioConnectorStore.findRecentPair(
                events(event("tt1:1:1", 1_000), event("tt1:1:2", 2_000)), now));
    }

    @Test
    public void matcherSkipsAReverseRetryAndKeepsOriginalPair() {
        long now = 1_000_000L;
        StremioConnectorStore.Pair pair = StremioConnectorStore.findRecentPair(events(
                event("tt1:1:4", now - 3_000),
                event("tt1:1:5", now - 2_000),
                event("tt1:1:4", now - 1_000)), now);

        assertNotNull(pair);
        assertEquals("tt1:1:4", pair.current.raw);
        assertEquals("tt1:1:5", pair.next.raw);
    }

    @Test
    public void metadataUsesExactEpisodeAndSuppressesFutureRelease() throws JSONException {
        StremioConnectorStore.Pair pair = pair("tt123:1:1", "tt123:1:2");
        String available = "{\"meta\":{\"name\":\"Bluey\","
                + "\"background\":\"https://example.test/background.jpg\","
                + "\"videos\":[{\"id\":\"tt123:1:2\",\"title\":\"BBQ\","
                + "\"thumbnail\":\"https://example.test/episode.jpg\","
                + "\"released\":\"2024-01-01T00:00:00.000Z\"}]}}";
        String future = available.replace("2024-01-01", "2030-01-01");

        NextEpisodeInfo info = NextEpisodeMetadataResolver.parse(
                pair, available, 1_800_000_000_000L);

        assertNotNull(info);
        assertEquals("Bluey", info.seriesTitle);
        assertEquals("BBQ", info.episodeTitle);
        assertEquals("https://example.test/episode.jpg", info.artworkUrl);
        assertNull(NextEpisodeMetadataResolver.parse(
                pair, future, 1_800_000_000_000L));
    }

    private static StremioConnectorStore.Pair pair(String current, String next) {
        return new StremioConnectorStore.Pair(
                StremioEpisodeId.parse(current), StremioEpisodeId.parse(next), 1L);
    }

    private static StremioConnectorStore.Event event(String id, long timestamp) {
        return new StremioConnectorStore.Event("series", id, timestamp);
    }

    private static List<StremioConnectorStore.Event> events(
            StremioConnectorStore.Event... events) {
        return Arrays.asList(events);
    }
}
