package com.nuvio.tv.ui.screens.player

/**
 * Isolated policy describing Nuvio RS's existing audio-aware subtitle behavior.
 *
 * Keeping this decision pure and tested protects the Plus profiles we rely on:
 * - foreign audio -> full preferred subtitles
 * - preferred/native audio -> forced subtitles only when enabled
 * - subtitles set to None can still allow forced signs/dialogue for matching preferred audio
 *
 * The player still uses upstream Nuvio language matching and track selection.
 */
internal object PlusAudioAwareSubtitleRules {
    data class Decision(
        val forcedTarget: String?,
        val targets: List<String>,
    ) {
        val forcedOnly: Boolean get() = forcedTarget != null
    }

    fun resolve(
        useForcedSubtitles: Boolean,
        preferredTargets: List<String>,
        selectedAudioMatchesPrimaryTarget: Boolean,
        fallbackForcedTarget: String?,
    ): Decision {
        val primaryTarget = preferredTargets.firstOrNull()
        val forcedTarget = when {
            !useForcedSubtitles -> null
            primaryTarget != null && selectedAudioMatchesPrimaryTarget -> primaryTarget
            primaryTarget == null -> fallbackForcedTarget
            else -> null
        }
        val targets = when {
            forcedTarget != null -> listOf(forcedTarget)
            primaryTarget != null -> preferredTargets
            else -> emptyList()
        }
        return Decision(forcedTarget = forcedTarget, targets = targets)
    }
}
