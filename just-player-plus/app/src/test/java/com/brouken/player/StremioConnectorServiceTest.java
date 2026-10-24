package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class StremioConnectorServiceTest {

    @Test
    public void connectorRestartsAfterRebootAndPackageReplacement() {
        assertTrue(StremioConnectorBootReceiver.restoresConnectorForAction(
                Intent.ACTION_BOOT_COMPLETED));
        assertTrue(StremioConnectorBootReceiver.restoresConnectorForAction(
                Intent.ACTION_MY_PACKAGE_REPLACED));
        assertFalse(StremioConnectorBootReceiver.restoresConnectorForAction(null));
        assertFalse(StremioConnectorBootReceiver.restoresConnectorForAction(
                Intent.ACTION_TIME_CHANGED));
    }

    @Test
    public void subtitlePreloadHasHardStartupDeadline() {
        assertEquals(1_500L, StremioConnectorOpenSubtitles.LOOKUP_TIMEOUT_MS);
    }

    @Test
    public void dispatchClientReturnsFalseAfterExecutorShutdown() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();

        assertFalse(StremioConnectorService.dispatchClient(executor, () -> { }));
    }

    @Test
    public void dispatchClientRunsTaskWhileExecutorIsActive() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            assertTrue(StremioConnectorService.dispatchClient(executor, completed::countDown));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void streamResponseReportsRuntimeFailureAndCountsReturnedStreams() {
        AtomicReference<String> error = new AtomicReference<>();

        String response = StremioConnectorService.streamResponse(
                true,
                () -> { throw new IllegalStateException("test"); },
                failure -> error.set(failure.getClass().getSimpleName()));

        assertEquals("{\"streams\":[]}", response);
        assertEquals("IllegalStateException", error.get());
        assertEquals(0, StremioConnectorService.streamCount(response));
        assertEquals(2, StremioConnectorService.streamCount(
                "{\"streams\":[{},{}]}"));
        assertEquals(-1, StremioConnectorService.streamCount("{\"other\":[]}"));
        assertEquals(-1, StremioConnectorService.streamCount("not-json"));
    }

    @Test
    public void prefetchAcceptsOnlyValidatedSeriesEpisodes() {
        assertTrue(StremioConnectorService.isValidPrefetchRequest(
                "series", "tt7678620:3:36"));
        assertFalse(StremioConnectorService.isValidPrefetchRequest(
                "movie", "tt7678620"));
        assertFalse(StremioConnectorService.isValidPrefetchRequest(
                "series", "tt7678620"));
        assertFalse(StremioConnectorService.isValidPrefetchRequest(null, null));
    }

    @Test
    public void replacementCancelsOnlyAnUnobservedOlderPrefetch() {
        assertTrue(StremioStreamAggregator.shouldCancelReplacedPrefetch(
                "episode-36", "episode-37", 0));
        assertFalse(StremioStreamAggregator.shouldCancelReplacedPrefetch(
                "episode-36", "episode-36", 0));
        assertFalse(StremioStreamAggregator.shouldCancelReplacedPrefetch(
                "episode-36", "episode-37", 1));
    }

    @Test
    public void protectedPrefetchSurvivesOnlyWithinItsBoundedLifetime() {
        long now = 10_000_000L;

        assertTrue(StremioProtectedPrefetchCache.isFresh(
                now - StremioProtectedPrefetchCache.MAX_AGE_MS, now));
        assertFalse(StremioProtectedPrefetchCache.isFresh(
                now - StremioProtectedPrefetchCache.MAX_AGE_MS - 1L, now));
        assertFalse(StremioProtectedPrefetchCache.isFresh(now + 1L, now));
    }

    @Test
    public void protectedPrefetchRejectsMalformedOrMismatchedResponses() {
        long now = 10_000_000L;

        assertTrue(StremioProtectedPrefetchCache.isStructurallyValid(
                new StremioProtectedPrefetchCache.Entry(
                        "episode-38", "{\"streams\":[{}]}", 1, now)));
        assertFalse(StremioProtectedPrefetchCache.isStructurallyValid(
                new StremioProtectedPrefetchCache.Entry(
                        "episode-38", "{\"streams\":[{}]}", 2, now)));
        assertFalse(StremioProtectedPrefetchCache.isStructurallyValid(
                new StremioProtectedPrefetchCache.Entry(
                        "episode-38", "not-json", 1, now)));
        assertFalse(StremioProtectedPrefetchCache.isStructurallyValid(
                new StremioProtectedPrefetchCache.Entry(
                        "episode-38", "{\"streams\":[]}", 0, now)));
    }

    @Test
    public void protectedPrefetchRequiresEveryEnabledSourceToCompleteCleanly() {
        assertTrue(StremioStreamAggregator.isCompleteSourceState("loaded"));
        assertTrue(StremioStreamAggregator.isCompleteSourceState("unsupported_type"));
        assertTrue(StremioStreamAggregator.isCompleteSourceState("unsupported_id"));
        assertTrue(StremioStreamAggregator.isCompleteSourceState(
                "missing_stream_resource"));
        assertFalse(StremioStreamAggregator.isCompleteSourceState("timeout"));
        assertFalse(StremioStreamAggregator.isCompleteSourceState("manifest_timeout"));
        assertFalse(StremioStreamAggregator.isCompleteSourceState("http_503"));
    }

    @Test
    public void foregroundAwaitsEveryHealthySourceButBackgroundsDegradedSources() {
        assertTrue(StremioStreamAggregator.shouldAwaitSource(true, false));
        assertFalse(StremioStreamAggregator.shouldAwaitSource(true, true));
        assertTrue(StremioStreamAggregator.shouldAwaitSource(false, false));
        assertTrue(StremioStreamAggregator.shouldAwaitSource(false, true));
        assertEquals(250L, StremioStreamAggregator.DEGRADED_PROBE_GRACE_MS);
    }

    @Test
    public void sourceWaitDefaultsToNineSecondsAndStaysWithinTvSliderRange() {
        StremioAggregationPreferences.Snapshot defaults =
                new StremioAggregationPreferences.Builder().build();
        StremioAggregationPreferences.Snapshot minimum =
                new StremioAggregationPreferences.Builder()
                        .setSourceWaitSeconds(-1)
                        .build();
        StremioAggregationPreferences.Snapshot maximum =
                new StremioAggregationPreferences.Builder()
                        .setSourceWaitSeconds(100)
                        .build();

        assertEquals(9, defaults.sourceWaitSeconds);
        assertEquals(9_000L, defaults.sourceWaitMs());
        assertEquals(3, minimum.sourceWaitSeconds);
        assertEquals(30, maximum.sourceWaitSeconds);
        assertFalse(defaults.cacheKey().equals(maximum.cacheKey()));
        assertEquals(3_000L, StremioAddonClient.MIN_SOURCE_WAIT_MS);
        assertEquals(30_000L, StremioAddonClient.MAX_SOURCE_WAIT_MS);
    }

    @Test
    public void sourceHealthRecoversOnSuccessAndDoesNotFollowAnEditedUrl() {
        long now = 10_000_000L;
        StremioSourceHealthTracker tracker = new StremioSourceHealthTracker();
        StremioStreamSourceStore.Source original = new StremioStreamSourceStore.Source(
                "12345678-1234-1234-1234-123456789012",
                "https://example.com/old/manifest.json",
                "Example",
                true);
        StremioStreamSourceStore.Source edited = original.withValues(
                "https://example.com/new/manifest.json", "Example", true);

        tracker.recordState(original, "timeout", now);
        assertTrue(tracker.isDegraded(original, now));
        assertTrue(tracker.isDegraded(
                original, now + StremioSourceHealthTracker.DEGRADED_AGE_MS));
        assertFalse(tracker.isDegraded(
                original, now + StremioSourceHealthTracker.DEGRADED_AGE_MS + 1L));

        tracker.recordState(original, "http_503", now);
        assertFalse(tracker.isDegraded(edited, now));
        tracker.recordState(original, "loaded", now + 1L);
        assertFalse(tracker.isDegraded(original, now + 1L));
        tracker.recordState(original, "cancelled", now + 2L);
        assertFalse(tracker.isDegraded(original, now + 2L));
    }

    @Test
    public void manifestCacheUsesOneHourFreshAnd24HourStaleWindows() {
        long now = 100_000_000L;

        assertTrue(StremioManifestCache.isFresh(
                now - StremioManifestCache.FRESH_AGE_MS, now));
        assertFalse(StremioManifestCache.isFresh(
                now - StremioManifestCache.FRESH_AGE_MS - 1L, now));
        assertTrue(StremioManifestCache.isUsableStale(
                now - StremioManifestCache.MAX_STALE_AGE_MS, now));
        assertFalse(StremioManifestCache.isUsableStale(
                now - StremioManifestCache.MAX_STALE_AGE_MS - 1L, now));
        assertFalse(StremioManifestCache.isUsableStale(now + 1L, now));
    }

    @Test
    public void manifestCachePersistsRoutingButNoUrlsOrArbitraryFields() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("name", "Private add-on")
                .put("endpoint", "https://example.com/token-value")
                .put("types", new JSONArray().put("movie").put("series"))
                .put("idPrefixes", new JSONArray().put("tt"))
                .put("resources", new JSONArray()
                        .put("catalog")
                        .put(new JSONObject()
                                .put("name", "stream")
                                .put("types", new JSONArray().put("series"))
                                .put("idPrefixes", new JSONArray().put("tt"))
                                .put("secret", "must-not-survive")));

        JSONObject sanitized = StremioManifestCache.sanitizeManifest(manifest);

        assertFalse(sanitized.has("name"));
        assertFalse(sanitized.has("endpoint"));
        assertFalse(sanitized.toString().contains("secret"));
        assertFalse(sanitized.toString().contains("token-value"));
        assertEquals("supported", StremioAddonClient.streamSupport(
                sanitized, "series", "tt1234567:1:2"));
        assertEquals("unsupported_type", StremioAddonClient.streamSupport(
                sanitized, "movie", "tt1234567"));
        assertFalse(StremioManifestCache.fingerprint(
                "https://example.com/a/manifest.json").equals(
                StremioManifestCache.fingerprint(
                        "https://example.com/b/manifest.json")));
    }

    @Test
    public void hardManifestFailuresInvalidateButTransientFailuresCanUseStaleData() {
        assertTrue(StremioAddonClient.isHardManifestFailure("http_401"));
        assertTrue(StremioAddonClient.isHardManifestFailure("http_403"));
        assertTrue(StremioAddonClient.isHardManifestFailure("http_404"));
        assertTrue(StremioAddonClient.isHardManifestFailure("http_410"));
        assertFalse(StremioAddonClient.isHardManifestFailure("http_429"));
        assertFalse(StremioAddonClient.isHardManifestFailure("http_503"));
        assertFalse(StremioAddonClient.isHardManifestFailure("timeout"));
    }
}
