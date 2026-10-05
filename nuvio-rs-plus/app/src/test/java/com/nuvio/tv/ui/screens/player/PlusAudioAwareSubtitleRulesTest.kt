package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlusAudioAwareSubtitleRulesTest {
    @Test
    fun foreignAudioUsesFullPreferredSubtitles() {
        val decision = PlusAudioAwareSubtitleRules.resolve(
            useForcedSubtitles = true,
            preferredTargets = listOf("cs"),
            selectedAudioMatchesPrimaryTarget = false,
            fallbackForcedTarget = null,
        )
        assertFalse(decision.forcedOnly)
        assertEquals(listOf("cs"), decision.targets)
    }

    @Test
    fun matchingAudioUsesForcedOnly() {
        val decision = PlusAudioAwareSubtitleRules.resolve(
            useForcedSubtitles = true,
            preferredTargets = listOf("cs"),
            selectedAudioMatchesPrimaryTarget = true,
            fallbackForcedTarget = null,
        )
        assertTrue(decision.forcedOnly)
        assertEquals("cs", decision.forcedTarget)
        assertEquals(listOf("cs"), decision.targets)
    }

    @Test
    fun subtitlesOffCanStillKeepForcedForPreferredAudio() {
        val decision = PlusAudioAwareSubtitleRules.resolve(
            useForcedSubtitles = true,
            preferredTargets = emptyList(),
            selectedAudioMatchesPrimaryTarget = false,
            fallbackForcedTarget = "cs",
        )
        assertTrue(decision.forcedOnly)
        assertEquals(listOf("cs"), decision.targets)
    }

    @Test
    fun forcedDisabledNeverCreatesForcedRule() {
        val decision = PlusAudioAwareSubtitleRules.resolve(
            useForcedSubtitles = false,
            preferredTargets = listOf("cs"),
            selectedAudioMatchesPrimaryTarget = true,
            fallbackForcedTarget = "cs",
        )
        assertFalse(decision.forcedOnly)
        assertEquals(listOf("cs"), decision.targets)
    }
}
