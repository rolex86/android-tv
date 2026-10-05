package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlusSubtitleSourcePreference

/**
 * Plus-only source tie-breaker. Language priority stays authoritative: source preference only
 * decides between candidates at the same (or better) preferred-language rank.
 */
internal object PlusSubtitleSourcePolicy {
    fun shouldPreferAddon(
        preference: PlusSubtitleSourcePreference,
        internalLanguageRank: Int?,
        addonLanguageRank: Int?,
    ): Boolean {
        if (preference != PlusSubtitleSourcePreference.ADDON || addonLanguageRank == null) return false
        return internalLanguageRank == null || addonLanguageRank <= internalLanguageRank
    }

    fun shouldWaitForAddon(
        preference: PlusSubtitleSourcePreference,
        addonLoading: Boolean,
        addonLanguageRank: Int?,
    ): Boolean =
        preference == PlusSubtitleSourcePreference.ADDON &&
            addonLoading &&
            addonLanguageRank == null
}
