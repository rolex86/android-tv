package com.brouken.player;

import java.util.concurrent.TimeUnit;

/**
 * Chooses a lightweight fallback polling interval for the next-episode popup.
 *
 * <p>The Media3 position message remains the primary trigger. This policy only protects against
 * streams replacing or adjusting their timeline after that message was armed.</p>
 */
final class NextEpisodePopupWatchdog {

    static final long RETRY_WITHOUT_DURATION_MS = TimeUnit.SECONDS.toMillis(5L);
    static final long FAR_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30L);
    static final long APPROACHING_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5L);
    static final long NEAR_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1L);

    private static final long APPROACHING_WINDOW_MS = TimeUnit.MINUTES.toMillis(5L);
    private static final long NEAR_WINDOW_MS = TimeUnit.MINUTES.toMillis(1L);

    private NextEpisodePopupWatchdog() {
    }

    static long nextDelayMs(long durationMs, long positionMs, long noticeMs) {
        if (durationMs <= 0L || positionMs < 0L) {
            return RETRY_WITHOUT_DURATION_MS;
        }

        long remainingUntilPopupMs = durationMs - positionMs - Math.max(0L, noticeMs);
        if (remainingUntilPopupMs <= 0L) {
            return 0L;
        }
        if (remainingUntilPopupMs <= NEAR_WINDOW_MS) {
            return NEAR_INTERVAL_MS;
        }
        if (remainingUntilPopupMs <= APPROACHING_WINDOW_MS) {
            return APPROACHING_INTERVAL_MS;
        }
        return FAR_INTERVAL_MS;
    }
}
