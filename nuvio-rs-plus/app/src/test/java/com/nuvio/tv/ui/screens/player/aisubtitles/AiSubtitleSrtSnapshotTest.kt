package com.nuvio.tv.ui.screens.player.aisubtitles

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSubtitleSrtSnapshotTest {
    @Test
    fun `serializes displayed sidecar timing to srt`() {
        val cues = listOf(
            CuesWithTiming(
                listOf(Cue.Builder().setText("Hello").build()),
                1_234_000L,
                2_000_000L,
            ),
            CuesWithTiming(
                listOf(
                    Cue.Builder().setText("Line one").build(),
                    Cue.Builder().setText("Line two").build(),
                ),
                3_500_000L,
                1_500_000L,
            ),
        )

        val srt = cues.toSrt()

        assertTrue(srt.contains("00:00:01,234 --> 00:00:03,234"))
        assertTrue(srt.contains("00:00:03,500 --> 00:00:05,000"))
        assertTrue(srt.contains("Line one\nLine two"))
        assertEquals(2, srt.split(" --> ").size - 1)
    }
}
