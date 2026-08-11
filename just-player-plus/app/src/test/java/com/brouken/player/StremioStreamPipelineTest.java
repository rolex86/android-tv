package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;

public class StremioStreamPipelineTest {

    @Test
    public void disabledGateReturnsExactVersion258ResponseWithoutInvokingAggregator() {
        AtomicBoolean invoked = new AtomicBoolean();

        String response = StremioConnectorService.streamResponse(false, () -> {
            invoked.set(true);
            return "{\"streams\":[{}]}";
        });

        assertEquals("{\"streams\":[]}", response);
        assertFalse(invoked.get());
    }

    @Test
    public void qualityOrderingInterleavesSourcesAndKeepsOriginalPlaybackFields() throws Exception {
        StremioStreamSourceStore.Source first = source(
                "11111111-1111-1111-1111-111111111111", "Webshare");
        StremioStreamSourceStore.Source second = source(
                "22222222-2222-2222-2222-222222222222", "Debrid Search");
        JSONObject first4k = direct(
                "https://video.test/a", "Movie.2160p.CZ.HEVC.DV.DDP5.1.mkv", 18_600_000_000L);
        first4k.put("customExtension", new JSONObject().put("kept", true));
        JSONObject second4k = direct(
                "https://video.test/b", "Movie.4K.EN.H264.HDR.mkv", 9_000_000_000L);
        JSONObject first4kAgain = direct(
                "https://video.test/c", "Movie.UHD.SK.HEVC.mkv", 12_000_000_000L);
        JSONObject second1080 = direct(
                "https://video.test/d", "Movie.1080p.CZ.H264.mkv", 5_000_000_000L);

        String response = StremioStreamPipeline.process(Arrays.asList(
                        sourceStreams(first, 0, first4k, first4kAgain),
                        sourceStreams(second, 1, second4k, second1080)),
                new StremioAggregationPreferences.Builder().build());
        JSONArray streams = new JSONObject(response).getJSONArray("streams");

        assertEquals(4, streams.length());
        assertEquals("4K • Webshare", streams.getJSONObject(0).getString("name"));
        assertEquals("4K • Debrid Search", streams.getJSONObject(1).getString("name"));
        assertEquals("4K • Webshare", streams.getJSONObject(2).getString("name"));
        assertEquals("1080p • Debrid Search", streams.getJSONObject(3).getString("name"));
        assertEquals("https://video.test/a", streams.getJSONObject(0).getString("url"));
        assertTrue(streams.getJSONObject(0)
                .getJSONObject("customExtension").getBoolean("kept"));
        assertEquals("kept", streams.getJSONObject(0)
                .getJSONObject("behaviorHints")
                .getJSONObject("proxyHeaders")
                .getJSONObject("request")
                .getString("Referer"));
        assertTrue(streams.getJSONObject(0).getString("title").contains("CZ"));
        assertTrue(streams.getJSONObject(0).getString("title").contains("HEVC"));
        assertEquals("jpp:v1:q:2160p", streams.getJSONObject(0)
                .getJSONObject("behaviorHints").getString("bingeGroup"));
    }

    @Test
    public void qualitySourceOrderingRanksFileSizeBeforeSourcePriority() throws Exception {
        StremioStreamSourceStore.Source first = source(
                "11111111-1111-1111-1111-111111111111", "First");
        StremioStreamSourceStore.Source second = source(
                "22222222-2222-2222-2222-222222222222", "Second");

        String response = StremioStreamPipeline.process(Arrays.asList(
                        sourceStreams(first, 0,
                                direct("https://video.test/first-small",
                                        "Show.1080p.first-small.mkv", 2_000_000_000L),
                                direct("https://video.test/first-large",
                                        "Show.1080p.first-large.mkv", 10_000_000_000L)),
                        sourceStreams(second, 1,
                                direct("https://video.test/second-large",
                                        "Show.1080p.second-large.mkv", 8_000_000_000L),
                                direct("https://video.test/second-small",
                                        "Show.1080p.second-small.mkv", 6_000_000_000L))),
                new StremioAggregationPreferences.Builder()
                        .setSortMode("quality_source")
                        .setSizeSort("larger")
                        .build());
        JSONArray streams = new JSONObject(response).getJSONArray("streams");

        assertEquals("https://video.test/first-large",
                streams.getJSONObject(0).getString("url"));
        assertEquals("https://video.test/second-large",
                streams.getJSONObject(1).getString("url"));
        assertEquals("https://video.test/second-small",
                streams.getJSONObject(2).getString("url"));
        assertEquals("https://video.test/first-small",
                streams.getJSONObject(3).getString("url"));
    }

    @Test
    public void perSourceLimitKeepsHighestRankedResultInsteadOfFirstUpstreamResult()
            throws Exception {
        StremioStreamSourceStore.Source source = source(
                "11111111-1111-1111-1111-111111111111", "Source");

        String response = StremioStreamPipeline.process(
                Collections.singletonList(sourceStreams(source, 0,
                        direct("https://video.test/small",
                                "Show.1080p.small.mkv", 2_000_000_000L),
                        direct("https://video.test/large",
                                "Show.1080p.large.mkv", 10_000_000_000L))),
                new StremioAggregationPreferences.Builder()
                        .setSortMode("quality_source")
                        .setSizeSort("larger")
                        .setMaxPerSource(1)
                        .build());
        JSONArray streams = new JSONObject(response).getJSONArray("streams");

        assertEquals(1, streams.length());
        assertEquals("https://video.test/large",
                streams.getJSONObject(0).getString("url"));
    }

    @Test
    public void explicitWeakMatchNeverDisplacesStrongMatchByFileSizeOrLimit()
            throws Exception {
        StremioStreamSourceStore.Source source = source(
                "11111111-1111-1111-1111-111111111111", "Webshare");
        JSONObject bluey = direct(
                "https://video.test/bluey",
                "Bluey.S01E05.1080p.WEB-DL.x264-CZ_SK_EN.mkv",
                350_080_000L)
                .put("strongMatch", true)
                .put("match", 0.92d);
        JSONObject killBlue = direct(
                "https://video.test/kill-blue",
                "KILL.BLUE.S01E05.1080p.WEB-DL.DUAL.DDP2.0.H264.mkv",
                1_500_000_000L)
                .put("strongMatch", false)
                .put("weakMatch", true)
                .put("match", 0.50d);

        StremioAggregationPreferences.Builder settings =
                new StremioAggregationPreferences.Builder()
                        .setSortMode("quality_source")
                        .setSizeSort("larger");
        JSONArray allStreams = new JSONObject(StremioStreamPipeline.process(
                Collections.singletonList(sourceStreams(source, 0, bluey, killBlue)),
                settings.build())).getJSONArray("streams");

        assertEquals(2, allStreams.length());
        assertEquals("https://video.test/bluey",
                allStreams.getJSONObject(0).getString("url"));
        assertEquals("https://video.test/kill-blue",
                allStreams.getJSONObject(1).getString("url"));

        JSONArray limitedStreams = new JSONObject(StremioStreamPipeline.process(
                Collections.singletonList(sourceStreams(source, 0, bluey, killBlue)),
                settings.setMaxPerSource(1).build())).getJSONArray("streams");

        assertEquals(1, limitedStreams.length());
        assertEquals("https://video.test/bluey",
                limitedStreams.getJSONObject(0).getString("url"));
        assertTrue(limitedStreams.getJSONObject(0).getBoolean("strongMatch"));
    }

    @Test
    public void safeDeduplicationUsesExactPlaybackIdentityAndHigherSourcePriority() throws Exception {
        StremioStreamSourceStore.Source preferred = source(
                "11111111-1111-1111-1111-111111111111", "Preferred");
        StremioStreamSourceStore.Source fallback = source(
                "22222222-2222-2222-2222-222222222222", "Fallback");
        JSONObject preferredUrl = direct(
                "https://video.test/file?token=one", "A.1080p.mkv", 1_000L);
        JSONObject exactDuplicate = direct(
                "https://video.test/file?token=one", "Other.1080p.mkv", 2_000L);
        JSONObject differentQuery = direct(
                "https://video.test/file?token=two", "A.1080p.mkv", 1_000L);
        JSONObject torrent = new JSONObject()
                .put("infoHash", "ABCDEF")
                .put("fileIdx", 2)
                .put("name", "1080p");
        JSONObject torrentDuplicate = new JSONObject(torrent.toString())
                .put("infoHash", "abcdef");

        String response = StremioStreamPipeline.process(Arrays.asList(
                        sourceStreams(preferred, 0, preferredUrl, torrent),
                        sourceStreams(fallback, 1,
                                exactDuplicate, differentQuery, torrentDuplicate)),
                new StremioAggregationPreferences.Builder().build());
        JSONArray streams = new JSONObject(response).getJSONArray("streams");

        assertEquals(3, streams.length());
        assertTrue(streams.getJSONObject(0).getString("name").contains("Preferred"));
        assertTrue(streams.getJSONObject(1).getString("name").contains("Fallback"));
        assertTrue(streams.getJSONObject(2).getString("name").contains("Preferred"));
    }

    @Test
    public void filtersReleaseLanguageSizeTypeAndUserTextConservatively() throws Exception {
        StremioStreamSourceStore.Source source = source(
                "11111111-1111-1111-1111-111111111111", "Source");
        JSONObject keep = direct(
                "https://video.test/keep", "Show.1080p.CZ.HEVC.mkv", 8_000_000_000L);
        JSONObject cam = direct(
                "https://video.test/cam", "Show.1080p.CZ.CAMRip.mkv", 2_000_000_000L);
        JSONObject english = direct(
                "https://video.test/en", "Show.1080p.EN.HEVC.mkv", 8_000_000_000L);
        JSONObject huge = direct(
                "https://video.test/huge", "Show.1080p.CZ.HEVC.mkv", 80_000_000_000L);
        JSONObject blocked = direct(
                "https://video.test/korsub", "Show.1080p.CZ.KORSUB.mkv", 8_000_000_000L);
        JSONObject external = new JSONObject()
                .put("externalUrl", "https://external.test/watch")
                .put("name", "1080p CZ HEVC");

        StremioAggregationPreferences.Snapshot settings =
                new StremioAggregationPreferences.Builder()
                        .setBlockedReleases(set("cam"))
                        .setAllowedLanguages(set("cz"))
                        .setMaxSizeGb(20d)
                        .setBlockedText(set("korsub"))
                        .setStreamTypes(set("direct"))
                        .build();
        JSONArray streams = new JSONObject(StremioStreamPipeline.process(
                Collections.singletonList(sourceStreams(
                        source, 0, keep, cam, english, huge, blocked, external)),
                settings)).getJSONArray("streams");

        assertEquals(1, streams.length());
        assertEquals("https://video.test/keep", streams.getJSONObject(0).getString("url"));
    }

    @Test
    public void diagnosticsCountEveryPipelineStageAndFilterReason() throws Exception {
        StremioStreamSourceStore.Source source = source(
                "11111111-1111-1111-1111-111111111111", "Source");
        JSONObject keep = direct(
                "https://video.test/keep", "Show.1080p.CZ.HEVC.mkv", 8_000_000_000L);
        JSONObject duplicate = new JSONObject(keep.toString());
        JSONObject wrongLanguage = direct(
                "https://video.test/en", "Show.1080p.EN.HEVC.mkv", 8_000_000_000L);
        JSONObject invalid = new JSONObject().put("name", "Show.1080p.CZ");

        StremioStreamPipeline.Result result = StremioStreamPipeline.processDetailed(
                Collections.singletonList(sourceStreams(
                        source, 0, keep, duplicate, wrongLanguage, invalid)),
                new StremioAggregationPreferences.Builder()
                        .setAllowedLanguages(set("cz"))
                        .build());

        assertEquals("loaded", result.state);
        assertEquals(4, result.stats.raw);
        assertEquals(1, result.stats.accepted);
        assertEquals(1, result.stats.returned);
        assertEquals(1, result.stats.duplicate);
        assertEquals(1, result.stats.invalid);
        assertEquals(Integer.valueOf(1), result.stats.rejected.get("language"));
        assertTrue(result.stats.summary().contains("filter_language=1"));
    }

    @Test
    public void bingeModesAreStableAndNonePreservesOriginalHint() throws Exception {
        StremioStreamSourceStore.Source source = source(
                "11111111-1111-1111-1111-111111111111", "Source");
        JSONObject stream = direct(
                "https://video.test/a", "Show.1080p.mkv", 1_000L);
        stream.getJSONObject("behaviorHints").put("bingeGroup", "upstream");

        JSONObject sourceQuality = firstStream(source, stream,
                new StremioAggregationPreferences.Builder()
                        .setBingeGroup("source_quality").build());
        assertEquals("jpp:v1:sq:11111111-1111-1111-1111-111111111111:1080p",
                sourceQuality.getJSONObject("behaviorHints").getString("bingeGroup"));

        JSONObject unchanged = firstStream(source, stream,
                new StremioAggregationPreferences.Builder()
                        .setBingeGroup("none").build());
        assertEquals("upstream",
                unchanged.getJSONObject("behaviorHints").getString("bingeGroup"));
    }

    @Test
    public void streamEndpointDerivationPreservesConfiguredPathAndQuery() {
        HttpUrl url = StremioAddonClient.buildStreamUrl(
                "https://addon.test/user-token/manifest.json?profile=one",
                "series",
                "tt123:2:4");

        assertNotNull(url);
        assertEquals("/user-token/stream/series/tt123:2:4.json", url.encodedPath());
        assertEquals("profile=one", url.encodedQuery());
        assertTrue(StremioAddonClient.parseManifestUrl(
                "http://local.test/config/manifest.json") != null);
        assertTrue(StremioAddonClient.parseManifestUrl(
                "https://addon.test/config") == null);
        assertTrue(StremioAddonClient.parseManifestUrl(
                "http://127.0.0.1:16745/manifest.json") == null);
    }

    @Test
    public void shortStreamResourceUsesManifestTypesAndIdPrefixes() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("resources", new JSONArray().put("stream"))
                .put("types", new JSONArray().put("series"))
                .put("idPrefixes", new JSONArray().put("tt"));

        assertEquals("supported", StremioAddonClient.streamSupport(
                manifest, "series", "tt7678620:3:37"));
        assertEquals("unsupported_type", StremioAddonClient.streamSupport(
                manifest, "movie", "tt7678620"));
        assertEquals("unsupported_id", StremioAddonClient.streamSupport(
                manifest, "series", "kitsu:123:1"));
    }

    @Test
    public void fullStreamResourceOverridesGlobalRouting() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("resources", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "stream")
                                .put("types", new JSONArray().put("series"))))
                .put("types", new JSONArray().put("movie"))
                .put("idPrefixes", new JSONArray().put("kitsu:"));

        assertEquals("supported", StremioAddonClient.streamSupport(
                manifest, "series", "tt7678620:3:37"));
        assertEquals("unsupported_type", StremioAddonClient.streamSupport(
                manifest, "movie", "kitsu:123"));
    }

    @Test
    public void multipleStreamDeclarationsMatchWhenAnyResourceSupportsRequest() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("resources", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "stream")
                                .put("types", new JSONArray().put("series"))
                                .put("idPrefixes", new JSONArray().put("kitsu:")))
                        .put(new JSONObject()
                                .put("name", "stream")
                                .put("types", new JSONArray().put("series"))
                                .put("idPrefixes", new JSONArray().put("tt"))));

        assertEquals("supported", StremioAddonClient.streamSupport(
                manifest, "series", "tt7678620:3:37"));
    }

    private static JSONObject firstStream(
            StremioStreamSourceStore.Source source,
            JSONObject stream,
            StremioAggregationPreferences.Snapshot settings) throws Exception {
        String response = StremioStreamPipeline.process(
                Collections.singletonList(sourceStreams(
                        source, 0, new JSONObject(stream.toString()))), settings);
        return new JSONObject(response).getJSONArray("streams").getJSONObject(0);
    }

    private static StremioStreamSourceStore.Source source(String id, String name) {
        return new StremioStreamSourceStore.Source(
                id, "https://addon.test/config/manifest.json", name, true);
    }

    private static StremioStreamPipeline.SourceStreams sourceStreams(
            StremioStreamSourceStore.Source source,
            int priority,
            JSONObject... streams) {
        return new StremioStreamPipeline.SourceStreams(
                source, new ArrayList<>(Arrays.asList(streams)), priority);
    }

    private static JSONObject direct(String url, String filename, long size) throws Exception {
        return new JSONObject()
                .put("url", url)
                .put("name", filename)
                .put("title", "Original provider description")
                .put("behaviorHints", new JSONObject()
                        .put("filename", filename)
                        .put("videoSize", size)
                        .put("proxyHeaders", new JSONObject()
                                .put("request", new JSONObject().put("Referer", "kept"))));
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
