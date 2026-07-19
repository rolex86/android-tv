package com.brouken.player;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * Delegates subtitle parsing to Media3 and shifts only the resulting cue timestamps.
 *
 * <p>This keeps the standard subtitle decoders and the complete audio/video renderer pipeline
 * untouched. Positive values display subtitles later; negative values display them earlier.
 */
final class OffsetSubtitleParserFactory implements SubtitleParser.Factory {

    interface DelayProvider {
        long getDelayUs();
    }

    private final SubtitleParser.Factory delegateFactory;
    private final DelayProvider delayProvider;

    OffsetSubtitleParserFactory(DelayProvider delayProvider) {
        this(new DefaultSubtitleParserFactory(), delayProvider);
    }

    OffsetSubtitleParserFactory(
            SubtitleParser.Factory delegateFactory, DelayProvider delayProvider) {
        this.delegateFactory = delegateFactory;
        this.delayProvider = delayProvider;
    }

    @Override
    public boolean supportsFormat(Format format) {
        return delegateFactory.supportsFormat(format);
    }

    @Override
    public int getCueReplacementBehavior(Format format) {
        return delegateFactory.getCueReplacementBehavior(format);
    }

    @Override
    public SubtitleParser create(Format format) {
        return new OffsetSubtitleParser(delegateFactory.create(format), delayProvider);
    }

    private static final class OffsetSubtitleParser implements SubtitleParser {
        private final SubtitleParser delegate;
        private final DelayProvider delayProvider;

        OffsetSubtitleParser(SubtitleParser delegate, DelayProvider delayProvider) {
            this.delegate = delegate;
            this.delayProvider = delayProvider;
        }

        @Override
        public void parse(
                byte[] data,
                int offset,
                int length,
                OutputOptions outputOptions,
                androidx.media3.common.util.Consumer<CuesWithTiming> output) {
            final long delayUs = delayProvider.getDelayUs();
            final OutputOptions adjustedOptions = adjustOutputOptions(outputOptions, delayUs);
            delegate.parse(
                    data,
                    offset,
                    length,
                    adjustedOptions,
                    cuesWithTiming -> output.accept(shift(cuesWithTiming, delayUs)));
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public int getCueReplacementBehavior() {
            return delegate.getCueReplacementBehavior();
        }
    }

    private static OutputOptions adjustOutputOptions(OutputOptions options, long delayUs) {
        if (delayUs == 0L || options.startTimeUs == C.TIME_UNSET) {
            return options;
        }
        // The delegate sees unshifted cue times, so move the requested threshold in the
        // opposite direction. This preserves correct cue emission after seeks.
        long delegateStartTimeUs = safeSubtract(options.startTimeUs, delayUs);
        return options.outputAllCues
                ? OutputOptions.cuesAfterThenRemainingCuesBefore(delegateStartTimeUs)
                : OutputOptions.onlyCuesAfter(delegateStartTimeUs);
    }

    private static CuesWithTiming shift(CuesWithTiming cuesWithTiming, long delayUs) {
        if (delayUs == 0L) {
            return cuesWithTiming;
        }
        long shiftedStartTimeUs;
        if (cuesWithTiming.startTimeUs == C.TIME_UNSET) {
            // TIME_UNSET means the cue starts at the containing sample timestamp. Converting it
            // to a relative offset makes SubtitleTranscodingTrackOutput apply the delay to that
            // sample timestamp as well.
            shiftedStartTimeUs = delayUs;
        } else {
            shiftedStartTimeUs = safeAdd(cuesWithTiming.startTimeUs, delayUs);
        }
        return new CuesWithTiming(
                cuesWithTiming.cues, shiftedStartTimeUs, cuesWithTiming.durationUs);
    }

    private static long safeAdd(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException ignored) {
            return delta >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long safeSubtract(long value, long delta) {
        try {
            return Math.subtractExact(value, delta);
        } catch (ArithmeticException ignored) {
            return delta >= 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
