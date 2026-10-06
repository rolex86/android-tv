package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlusHomePaginationPrefetchTest {

    @Test
    fun activeRowStartsPrefetchMuchEarlier() {
        assertTrue(
            PlusHomePaginationPrefetch.isNearEnd(
                lastVisible = 6,
                total = 20,
                activeRow = true
            )
        )
    }

    @Test
    fun inactiveRowKeepsConservativeThreshold() {
        assertFalse(
            PlusHomePaginationPrefetch.isNearEnd(
                lastVisible = 6,
                total = 20,
                activeRow = false
            )
        )
        assertTrue(
            PlusHomePaginationPrefetch.isNearEnd(
                lastVisible = 16,
                total = 20,
                activeRow = false
            )
        )
    }

    @Test
    fun laterPagesAlsoKeepFourteenItemRunwayOnActiveRow() {
        assertFalse(
            PlusHomePaginationPrefetch.isNearEnd(
                lastVisible = 25,
                total = 40,
                activeRow = true
            )
        )
        assertTrue(
            PlusHomePaginationPrefetch.isNearEnd(
                lastVisible = 26,
                total = 40,
                activeRow = true
            )
        )
    }
}
