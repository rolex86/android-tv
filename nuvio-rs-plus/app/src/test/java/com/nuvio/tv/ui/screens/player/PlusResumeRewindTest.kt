package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlusResumeRewindTest {
    @Test
    fun disabledIsStrictNoOp() {
        assertEquals(123_456L, PlusResumeRewind.apply(123_456L, 0))
    }

    @Test
    fun rewindsConfiguredSeconds() {
        assertEquals(113_456L, PlusResumeRewind.apply(123_456L, 10))
    }

    @Test
    fun neverSeeksBeforeStart() {
        assertEquals(0L, PlusResumeRewind.apply(4_000L, 10))
    }

    @Test
    fun clampsUnexpectedPreferenceValues() {
        assertEquals(70_000L, PlusResumeRewind.apply(100_000L, 60))
    }
}
