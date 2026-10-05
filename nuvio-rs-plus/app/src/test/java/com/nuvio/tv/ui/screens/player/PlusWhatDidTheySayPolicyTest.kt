package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlusWhatDidTheySayPolicyTest {
    @Test
    fun rewindsTenSeconds() {
        assertEquals(50_000L, PlusWhatDidTheySayPolicy.rewindTarget(60_000L))
    }

    @Test
    fun rewindNeverCrossesStart() {
        assertEquals(0L, PlusWhatDidTheySayPolicy.rewindTarget(4_000L))
    }

    @Test
    fun subtitlesRemainUntilFiveSecondsPastOriginalPoint() {
        assertEquals(65_000L, PlusWhatDidTheySayPolicy.restoreAtPosition(60_000L))
    }
}
