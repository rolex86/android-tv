package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.mergeCatalogPage

/**
 * Plus-only guard for Modern Home pagination.
 *
 * A page response contributes items and pagination state, but must never be allowed
 * to replace the identity of the row that requested it. Modern Home keys rows by
 * addonBaseUrl as well as addon/catalog ID; changing that identity while the row
 * holds focus makes Compose dispose the focused row.
 */
internal fun CatalogRow.mergeCatalogPagePreservingIdentity(page: CatalogRow): CatalogRow {
    val sameIdentityPage = page.copy(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        extraArgs = extraArgs
    )
    return mergeCatalogPage(sameIdentityPage)
}
