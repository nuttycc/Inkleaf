package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discover grid's structure used to be derived twice — once to map comic indexes for scroll
 * anchors and once to render — so drift silently sent anchor restoration to the wrong item. These
 * tests pin the single derivation.
 */
class DiscoverGridLayoutTest {
    private val first = DiscoverComicKey("plugin", "first")
    private val second = DiscoverComicKey("plugin", "second")
    private val third = DiscoverComicKey("plugin", "third")

    private val idleBrowse =
        DiscoverBrowseGridInput(
            feedCount = 1,
            hasSelectedFeed = true,
            isLoadingFeeds = false,
            hasFeedLoadError = false,
            hasBrowseError = false,
            isBrowsing = false,
            hasMore = false,
        )

    @Test
    fun `browse trailing progress follows the comics while paging in`() {
        val layout =
            browseLayout(
                input = idleBrowse.copy(isBrowsing = true, hasMore = true),
                comicKeys = listOf(first),
            )

        assertTrue(layout.leading.isEmpty())
        assertEquals(listOf(DiscoverStructuralItem.BROWSE_PAGING), layout.trailing)
        assertEquals(0, layout.comicItems.single().gridIndex)
    }

    @Test
    fun `browse progress leads while the first page is still empty`() {
        val layout =
            browseLayout(input = idleBrowse.copy(isBrowsing = true), comicKeys = emptyList())

        assertEquals(listOf(DiscoverStructuralItem.BROWSE_PROGRESS), layout.leading)
        assertTrue(layout.trailing.isEmpty())
        assertFalse(layout.hasComics)
    }

    @Test
    fun `an empty settled feed shows its empty state and no load more`() {
        val layout = browseLayout(input = idleBrowse.copy(hasMore = true), comicKeys = emptyList())

        assertEquals(listOf(DiscoverStructuralItem.BROWSE_EMPTY), layout.leading)
        assertTrue(layout.trailing.isEmpty())
        assertFalse(layout.hasComics)
    }

    @Test
    fun `a browse error suppresses the empty state and still offers load more`() {
        val layout =
            browseLayout(
                input = idleBrowse.copy(hasBrowseError = true, hasMore = true),
                comicKeys = emptyList(),
            )

        assertEquals(listOf(DiscoverStructuralItem.BROWSE_ERROR), layout.leading)
        assertEquals(listOf(DiscoverStructuralItem.LOAD_MORE), layout.trailing)
    }

    @Test
    fun `a settled feed with items has no structural entries around it`() {
        val layout =
            browseLayout(input = idleBrowse, comicKeys = listOf(first, second))

        assertTrue(layout.leading.isEmpty())
        assertTrue(layout.trailing.isEmpty())
        assertEquals(listOf(0, 1), layout.comicItems.map { it.gridIndex })
    }

    @Test
    fun `no feeds at all shows exactly one entry`() {
        val loading =
            browseLayout(input = idleBrowse.copy(feedCount = 0, isLoadingFeeds = true))
        val failed =
            browseLayout(input = idleBrowse.copy(feedCount = 0, hasFeedLoadError = true))
        val unsupported = browseLayout(input = idleBrowse.copy(feedCount = 0))

        assertEquals(listOf(DiscoverStructuralItem.FEED_LOADING), loading.leading)
        assertEquals(listOf(DiscoverStructuralItem.FEED_EMPTY_ERROR), failed.leading)
        assertEquals(listOf(DiscoverStructuralItem.FEED_EMPTY_TEXT), unsupported.leading)
        assertTrue(loading.comicItems.isEmpty())
    }

    @Test
    fun `a feed error banner leads the comics`() {
        val layout =
            browseLayout(input = idleBrowse.copy(hasFeedLoadError = true), comicKeys = listOf(first))

        assertEquals(listOf(DiscoverStructuralItem.FEED_ERROR), layout.leading)
        assertEquals(1, layout.comicItems.single().gridIndex)
    }

    @Test
    fun `a feed and a browse error banner both lead the comics`() {
        val layout =
            browseLayout(
                input = idleBrowse.copy(hasFeedLoadError = true, hasBrowseError = true),
                comicKeys = listOf(first),
            )

        assertEquals(
            listOf(DiscoverStructuralItem.FEED_ERROR, DiscoverStructuralItem.BROWSE_ERROR),
            layout.leading,
        )
        assertEquals(2, layout.comicItems.single().gridIndex)
    }

    @Test
    fun `no selected feed yields no structure at all`() {
        val layout =
            browseLayout(input = idleBrowse.copy(hasSelectedFeed = false), comicKeys = listOf(first))

        assertTrue(layout.leading.isEmpty())
        assertTrue(layout.sections.isEmpty())
        assertTrue(layout.comicItems.isEmpty())
    }

    @Test
    fun `search leads with progress, then error, then the empty state`() {
        val layout = searchLayout(query = "comic", isSearching = true, hasError = true, groups = emptyList())

        assertEquals(
            listOf(DiscoverStructuralItem.SEARCH_PROGRESS, DiscoverStructuralItem.SEARCH_ERROR),
            layout.leading,
        )
        assertTrue(layout.comicItems.isEmpty())
    }

    @Test
    fun `search leads with the empty state once nothing is in flight`() {
        val layout = searchLayout(query = "comic", isSearching = false, hasError = false, groups = emptyList())

        assertEquals(listOf(DiscoverStructuralItem.SEARCH_EMPTY), layout.leading)
        assertTrue(layout.sections.isEmpty())
        assertTrue(layout.comicItems.isEmpty())
    }

    @Test
    fun `search with a blank query never shows the empty state`() {
        val layout = searchLayout(query = "", isSearching = false, hasError = false, groups = emptyList())

        assertTrue(layout.leading.none { it == DiscoverStructuralItem.SEARCH_EMPTY })
    }

    @Test
    fun `search results are grouped one section per source`() {
        val layout =
            searchLayout(
                query = "comic",
                isSearching = false,
                hasError = false,
                groups = listOf(group(listOf(first, second)), group(listOf(third))),
            )

        assertEquals(2, layout.sections.size)
        // Source header, two comics, second source header, one comic.
        assertEquals(listOf(1, 2, 4), layout.comicItems.map { it.gridIndex })
    }

    @Test
    fun `a failed source group emits its error and no comics`() {
        val layout =
            searchLayout(
                query = "comic",
                groups = listOf(group(listOf(first), hasError = true)),
            )

        assertEquals(
            listOf(DiscoverStructuralItem.SOURCE_HEADER, DiscoverStructuralItem.SOURCE_ERROR),
            layout.sections.single().structuralItems,
        )
        assertTrue(layout.sections.single().comicKeys.isEmpty())
    }

    @Test
    fun `an empty source group among others still occupies one structural item`() {
        val layout =
            searchLayout(query = "comic", groups = listOf(group(listOf(first)), group(emptyList())))

        assertEquals(2, layout.sections.size)
        assertEquals(
            listOf(DiscoverStructuralItem.SOURCE_HEADER, DiscoverStructuralItem.SOURCE_EMPTY),
            layout.sections.last().structuralItems,
        )
        assertEquals(listOf(1), layout.comicItems.map { it.gridIndex })
    }

    @Test
    fun `structural items always match the section's structural count`() {
        val layout =
            searchLayout(
                query = "comic",
                groups =
                    listOf(
                        group(listOf(first)),
                        group(emptyList()),
                        group(emptyList(), hasError = true),
                    ),
            )

        layout.sections.forEach { section ->
            assertEquals(section.structuralItemCount, section.structuralItems.size)
        }
    }

    @Test
    fun `no results predicate is true when every group is empty`() {
        assertTrue(hasNoResults(group(emptyList()), group(emptyList())))
        assertFalse(hasNoResults(group(listOf(first)), group(emptyList())))
    }

    @Test
    fun `a group with an error is not an empty result`() {
        assertFalse(hasNoResults(group(emptyList(), hasError = true)))
    }

    private fun browseLayout(
        input: DiscoverBrowseGridInput,
        comicKeys: List<DiscoverComicKey> = emptyList(),
    ) = discoverBrowseGridLayout(input = input, comicKeys = comicKeys)

    private fun searchLayout(
        query: String = "comic",
        isSearching: Boolean = false,
        hasError: Boolean = false,
        groups: List<DiscoverSearchGroupInput> = emptyList(),
    ) =
        discoverSearchGridLayout(
            DiscoverSearchGridInput(
                query = query,
                isSearching = isSearching,
                hasError = hasError,
                groups = groups,
            )
        )

    private fun hasNoResults(vararg groups: DiscoverSearchGroupInput) =
        discoverSearchHasNoResults(
            DiscoverSearchGridInput(
                query = "comic",
                isSearching = false,
                hasError = false,
                groups = groups.toList(),
            )
        )

    private fun group(keys: List<DiscoverComicKey>, hasError: Boolean = false) =
        DiscoverSearchGroupInput(comicKeys = keys, hasError = hasError)
}
