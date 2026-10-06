package com.nuvio.tv.ui.screens.stream

/**
 * Plus-only guard for persisted binge-group reuse when entering source selection.
 *
 * A binge group represents continuity across episodes. Reusing it for a standalone
 * movie breaks MANUAL stream selection because a previously chosen movie source can
 * silently become an auto-play target on the next visit.
 */
internal object PlusBingeGroupReusePolicy {
    fun allowForInitialSelection(
        season: Int?,
        episode: Int?
    ): Boolean = season != null && episode != null
}
