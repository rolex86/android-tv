package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

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
    public void foregroundResultUsesShortGraceWithoutShorteningBackgroundPrefetch() {
        long totalDeadlineNanos = TimeUnit.MILLISECONDS.toNanos(
                StremioStreamAggregator.TOTAL_DEADLINE_MS);
        long firstUsableResultNanos = TimeUnit.MILLISECONDS.toNanos(2_000L);
        long graceDeadlineNanos = firstUsableResultNanos
                + TimeUnit.MILLISECONDS.toNanos(
                StremioStreamAggregator.FOREGROUND_RESULT_GRACE_MS);

        assertEquals(totalDeadlineNanos, StremioStreamAggregator.effectiveDeadlineNanos(
                totalDeadlineNanos, 0L, true));
        assertEquals(totalDeadlineNanos, StremioStreamAggregator.effectiveDeadlineNanos(
                totalDeadlineNanos, firstUsableResultNanos, false));
        assertEquals(graceDeadlineNanos, StremioStreamAggregator.effectiveDeadlineNanos(
                totalDeadlineNanos, firstUsableResultNanos, true));
        assertTrue(StremioStreamAggregator.startsForegroundGrace("loaded", 1));
        assertFalse(StremioStreamAggregator.startsForegroundGrace("loaded", 0));
        assertFalse(StremioStreamAggregator.startsForegroundGrace("timeout", 1));
    }

    @Test
    public void foregroundGraceNeverExtendsTheTotalAggregationDeadline() {
        long totalDeadlineNanos = TimeUnit.MILLISECONDS.toNanos(9_000L);
        long lateUsableResultNanos = TimeUnit.MILLISECONDS.toNanos(8_500L);

        assertEquals(totalDeadlineNanos, StremioStreamAggregator.effectiveDeadlineNanos(
                totalDeadlineNanos, lateUsableResultNanos, true));
    }
}
