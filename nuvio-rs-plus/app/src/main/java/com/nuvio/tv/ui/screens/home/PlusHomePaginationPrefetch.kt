package com.nuvio.tv.ui.screens.home

/**
 * Plus-only pagination prefetch policy for Modern Home.
 *
 * The active row gets a much larger runway so a user holding DPAD_RIGHT is less
 * likely to hit the end of the currently loaded page before the network response
 * arrives. Inactive rows keep the conservative upstream threshold to avoid
 * needlessly paginating every catalog in the background.
 */
internal object PlusHomePaginationPrefetch {
    private const val ACTIVE_ROW_DISTANCE = 14
    private const val INACTIVE_ROW_DISTANCE = 4

    fun distance(activeRow: Boolean): Int =
        if (activeRow) ACTIVE_ROW_DISTANCE else INACTIVE_ROW_DISTANCE

    fun isNearEnd(
        lastVisible: Int,
        total: Int,
        activeRow: Boolean
    ): Boolean {
        if (total <= 0) return false
        return lastVisible >= total - distance(activeRow)
    }
}
