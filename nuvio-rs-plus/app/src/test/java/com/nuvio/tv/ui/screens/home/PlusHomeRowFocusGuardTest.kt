package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlusHomeRowFocusGuardTest {
    @Test
    fun ltrRightRepeatMovesOnlyToNextItem() {
        assertEquals(
            PlusHomeRowFocusGuard.Decision.Move(4),
            PlusHomeRowFocusGuard.resolveRepeat(
                currentIndex = 3,
                itemCount = 10,
                direction = PlusHomeRowFocusGuard.Direction.RIGHT,
                isRtl = false,
            )
        )
    }

    @Test
    fun ltrRightRepeatAtEndIsBlockedInsteadOfEscapingRow() {
        assertTrue(
            PlusHomeRowFocusGuard.resolveRepeat(
                currentIndex = 9,
                itemCount = 10,
                direction = PlusHomeRowFocusGuard.Direction.RIGHT,
                isRtl = false,
            ) is PlusHomeRowFocusGuard.Decision.Block
        )
    }

    @Test
    fun ltrLeftAtFirstItemPassesThroughForSidebarNavigation() {
        assertTrue(
            PlusHomeRowFocusGuard.resolveRepeat(
                currentIndex = 0,
                itemCount = 10,
                direction = PlusHomeRowFocusGuard.Direction.LEFT,
                isRtl = false,
            ) is PlusHomeRowFocusGuard.Decision.PassThrough
        )
    }

    @Test
    fun rtlUsesMirroredHorizontalDirection() {
        assertEquals(
            PlusHomeRowFocusGuard.Decision.Move(4),
            PlusHomeRowFocusGuard.resolveRepeat(
                currentIndex = 3,
                itemCount = 10,
                direction = PlusHomeRowFocusGuard.Direction.LEFT,
                isRtl = true,
            )
        )
        assertTrue(
            PlusHomeRowFocusGuard.resolveRepeat(
                currentIndex = 0,
                itemCount = 10,
                direction = PlusHomeRowFocusGuard.Direction.RIGHT,
                isRtl = true,
            ) is PlusHomeRowFocusGuard.Decision.PassThrough
        )
    }
}
