package com.brouken.player;

import java.util.concurrent.TimeUnit;

/** Playback-position policy for warming the Connector before Stremio needs the next episode. */
final class NextEpisodeStreamPrefetchPolicy {
    static final long LEAD_MS = TimeUnit.MINUTES.toMillis(3L);

    private NextEpisodeStreamPrefetchPolicy() {
    }

    static long triggerPositionMs(long durationMs) {
        if (durationMs <= 0L) {
            return -1L;
        }
        return Math.max(0L, durationMs - LEAD_MS);
    }

    static boolean shouldStart(long durationMs, long positionMs) {
        long triggerPositionMs = triggerPositionMs(durationMs);
        return triggerPositionMs >= 0L && positionMs >= triggerPositionMs;
    }
}
