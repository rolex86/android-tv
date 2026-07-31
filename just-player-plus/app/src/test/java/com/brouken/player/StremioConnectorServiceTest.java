package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StremioConnectorServiceTest {

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
}
