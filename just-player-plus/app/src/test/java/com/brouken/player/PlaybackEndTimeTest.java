package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackEndTimeTest {
    @Test
    public void normalSpeedAddsRemainingMediaTime() {
        assertEquals(1_060_000L,
                PlaybackEndTime.estimateEpochMs(1_000_000L, 120_000L, 60_000L, 1f));
    }

    @Test
    public void fasterPlaybackShortensWallClockRemainder() {
        assertEquals(1_040_000L,
                PlaybackEndTime.estimateEpochMs(1_000_000L, 120_000L, 60_000L, 1.5f));
    }

    @Test
    public void invalidOrUnknownMediaCannotProduceEstimate() {
        assertEquals(PlaybackEndTime.INVALID,
                PlaybackEndTime.estimateEpochMs(1_000L, 0L, 0L, 1f));
        assertEquals(PlaybackEndTime.INVALID,
                PlaybackEndTime.estimateEpochMs(1_000L, 10_000L, 11_000L, 1f));
        assertEquals(PlaybackEndTime.INVALID,
                PlaybackEndTime.estimateEpochMs(1_000L, 10_000L, 1_000L, 0f));
    }

    @Test
    public void hugeEstimateSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, PlaybackEndTime.estimateEpochMs(
                Long.MAX_VALUE - 5L, Long.MAX_VALUE, 0L, 0.5f));
    }
}
