package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PlusCatalogPaginationIdentityTest {

    @Test
    fun paginationCannotReplaceStableRowIdentity() {
        val original = row(
            baseUrl = "https://primary.example/addon",
            items = emptyList(),
            currentPage = 0,
            nextSkip = 20
        )
        val pageFromWrongInstance = row(
            baseUrl = "https://other.example/addon",
            items = emptyList(),
            currentPage = 1,
            nextSkip = 40
        )

        val originalKey = original.stableKey()
        val merged = original.mergeCatalogPagePreservingIdentity(pageFromWrongInstance)

        assertEquals(originalKey, merged.stableKey())
        assertEquals(original.addonBaseUrl, merged.addonBaseUrl)
        assertEquals(original.addonId, merged.addonId)
        assertEquals(original.catalogId, merged.catalogId)
    }

    private fun row(
        baseUrl: String,
        items: List<MetaPreview>,
        currentPage: Int,
        nextSkip: Int
    ) = CatalogRow(
        addonId = "com.nuvio.tmdb.catalogs",
        addonName = "TMDB",
        addonBaseUrl = baseUrl,
        catalogId = "new-and-upcoming-movies",
        catalogName = "New and Upcoming Movies",
        type = ContentType.MOVIE,
        rawType = "movie",
        items = items,
        hasMore = true,
        currentPage = currentPage,
        supportsSkip = true,
        skipStep = 20,
        nextSkip = nextSkip
    )
}
