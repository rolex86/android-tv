package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import androidx.media3.common.C;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser.OutputOptions;

import org.junit.Test;

import java.util.Collections;

public class OffsetSubtitleParserFactoryTest {

    @Test
    public void zeroDelayReturnsOriginalCueObject() {
        CuesWithTiming cues = cuesAt(5_000_000L);

        assertSame(cues, OffsetSubtitleParserFactory.shift(cues, 0L));
    }

    @Test
    public void cueTimesShiftInBothDirections() {
        assertEquals(6_000_000L,
                OffsetSubtitleParserFactory.shift(
                        cuesAt(5_000_000L), 1_000_000L).startTimeUs);
        assertEquals(4_000_000L,
                OffsetSubtitleParserFactory.shift(
                        cuesAt(5_000_000L), -1_000_000L).startTimeUs);
    }

    @Test
    public void unsetCueTimeBecomesRelativeDelay() {
        assertEquals(-500_000L,
                OffsetSubtitleParserFactory.shift(
                        cuesAt(C.TIME_UNSET), -500_000L).startTimeUs);
    }

    @Test
    public void seekThresholdMovesOppositeToDelay() {
        OutputOptions original = OutputOptions.onlyCuesAfter(30_000_000L);

        OutputOptions adjusted = OffsetSubtitleParserFactory.adjustOutputOptions(
                original, 2_000_000L);

        assertEquals(28_000_000L, adjusted.startTimeUs);
    }

    @Test
    public void timestampMathSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE,
                OffsetSubtitleParserFactory.safeAdd(Long.MAX_VALUE - 2L, 10L));
        assertEquals(Long.MIN_VALUE,
                OffsetSubtitleParserFactory.safeAdd(Long.MIN_VALUE + 2L, -10L));
        assertEquals(Long.MIN_VALUE,
                OffsetSubtitleParserFactory.safeSubtract(Long.MIN_VALUE + 2L, 10L));
        assertEquals(Long.MAX_VALUE,
                OffsetSubtitleParserFactory.safeSubtract(Long.MAX_VALUE - 2L, -10L));
    }

    private static CuesWithTiming cuesAt(long startTimeUs) {
        return new CuesWithTiming(Collections.emptyList(), startTimeUs, 1_000_000L);
    }
}
