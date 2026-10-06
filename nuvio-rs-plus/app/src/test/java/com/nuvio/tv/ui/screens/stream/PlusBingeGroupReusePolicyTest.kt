package com.nuvio.tv.ui.screens.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlusBingeGroupReusePolicyTest {
    @Test
    fun movieNeverReusesPersistedBingeGroupForInitialSelection() {
        assertFalse(PlusBingeGroupReusePolicy.allowForInitialSelection(null, null))
    }

    @Test
    fun incompleteEpisodeIdentityDoesNotBypassPicker() {
        assertFalse(PlusBingeGroupReusePolicy.allowForInitialSelection(1, null))
        assertFalse(PlusBingeGroupReusePolicy.allowForInitialSelection(null, 1))
    }

    @Test
    fun episodeCanReusePersistedBingeGroup() {
        assertTrue(PlusBingeGroupReusePolicy.allowForInitialSelection(1, 2))
    }
}
