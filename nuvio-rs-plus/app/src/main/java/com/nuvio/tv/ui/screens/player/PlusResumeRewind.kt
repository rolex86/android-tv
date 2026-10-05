package com.nuvio.tv.ui.screens.player

/**
 * Plus-only resume offset. A zero value is a strict no-op so this can be removed
 * cleanly if upstream Nuvio RS later provides an equivalent setting.
 */
internal object PlusResumeRewind {
    fun apply(positionMs: Long, rewindSeconds: Int): Long {
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeSeconds = rewindSeconds.coerceIn(0, 30)
        if (safePosition == 0L || safeSeconds == 0) return safePosition
        return (safePosition - safeSeconds * 1_000L).coerceAtLeast(0L)
    }
}
