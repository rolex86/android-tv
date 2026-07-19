package com.brouken.player;

/** Pure calculation for the projected wall-clock end of finite playback. */
final class PlaybackEndTime {
    static final long INVALID = -1L;

    private PlaybackEndTime() {
    }

    static long estimateEpochMs(long nowMs,
                                long durationMs,
                                long positionMs,
                                float playbackSpeed) {
        if (nowMs < 0L || durationMs <= 0L || positionMs < 0L
                || positionMs > durationMs || Float.isNaN(playbackSpeed)
                || Float.isInfinite(playbackSpeed)
                || playbackSpeed <= 0f) {
            return INVALID;
        }
        double remainingWallClockMs = (durationMs - positionMs) / (double) playbackSpeed;
        if (remainingWallClockMs >= Long.MAX_VALUE - nowMs) {
            return Long.MAX_VALUE;
        }
        return nowMs + Math.round(remainingWallClockMs);
    }
}
