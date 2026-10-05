package com.nuvio.tv.ui.screens.home

/**
 * Plus-only guard for repeated horizontal D-pad navigation on Modern Home rows.
 *
 * Nuvio's generic repeat throttle uses FocusManager.moveFocus(), which can select a card in
 * another row when the next LazyRow item is temporarily not composed (for example while the
 * row is paging). This policy keeps repeat navigation inside the active row. A move toward
 * the start edge is intentionally passed through so the normal sidebar navigation still works.
 *
 * Remove this helper and the two small hooks when upstream Nuvio provides equivalent
 * row-confined horizontal focus navigation.
 */
internal object PlusHomeRowFocusGuard {
    enum class Direction {
        LEFT,
        RIGHT,
    }

    sealed interface Decision {
        data class Move(val targetIndex: Int) : Decision
        data object Block : Decision
        data object PassThrough : Decision
    }

    fun resolveRepeat(
        currentIndex: Int,
        itemCount: Int,
        direction: Direction,
        isRtl: Boolean,
    ): Decision {
        if (itemCount <= 0) return Decision.PassThrough

        val index = currentIndex.coerceIn(0, itemCount - 1)
        val delta = when {
            !isRtl && direction == Direction.RIGHT -> 1
            !isRtl && direction == Direction.LEFT -> -1
            isRtl && direction == Direction.LEFT -> 1
            else -> -1
        }
        val target = index + delta

        return when {
            target in 0 until itemCount -> Decision.Move(target)
            target < 0 -> Decision.PassThrough
            else -> Decision.Block
        }
    }
}
