package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlusSubtitleSourcePreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlusSubtitleSourcePolicyTest {
    @Test
    fun autoNeverOverridesUpstreamOrdering() {
        assertFalse(
            PlusSubtitleSourcePolicy.shouldPreferAddon(
                PlusSubtitleSourcePreference.AUTO,
                internalLanguageRank = 0,
                addonLanguageRank = 0,
            )
        )
    }

    @Test
    fun addonWinsTieWithinSameLanguage() {
        assertTrue(
            PlusSubtitleSourcePolicy.shouldPreferAddon(
                PlusSubtitleSourcePreference.ADDON,
                internalLanguageRank = 0,
                addonLanguageRank = 0,
            )
        )
    }

    @Test
    fun higherPriorityEmbeddedLanguageStillWins() {
        assertFalse(
            PlusSubtitleSourcePolicy.shouldPreferAddon(
                PlusSubtitleSourcePreference.ADDON,
                internalLanguageRank = 0,
                addonLanguageRank = 1,
            )
        )
    }

    @Test
    fun addonPreferenceWaitsWhileAddonPoolIsStillLoading() {
        assertTrue(
            PlusSubtitleSourcePolicy.shouldWaitForAddon(
                PlusSubtitleSourcePreference.ADDON,
                addonLoading = true,
                addonLanguageRank = null,
            )
        )
    }
}
