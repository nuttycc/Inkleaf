package com.exio.inkleaf.ui

/**
 * One structural entry in the discover grid: a header, error, progress indicator, or empty state.
 *
 * The discover grid used to derive its leading and trailing counts twice — once to map comic indexes
 * for scroll anchors and once to render — so any drift silently sent anchor restoration to the wrong
 * item with nothing failing. Naming the entries lets both consumers read one sequence instead.
 */
enum class DiscoverStructuralItem {
    /** First feed list still loading. */
    FEED_LOADING,

    /** No enabled source offers browse at all. */
    FEED_EMPTY_TEXT,

    /** Loading the first feed list failed. */
    FEED_EMPTY_ERROR,

    /** Feed error banner shown above browse content. */
    FEED_ERROR,

    /** Browse error banner shown above the comic list. */
    BROWSE_ERROR,

    /** Centered progress while the first page of a feed is loading. */
    BROWSE_PROGRESS,

    /** The selected feed has no content at all. */
    BROWSE_EMPTY,

    /** Manual "load more" retry after prefetch failed. */
    LOAD_MORE,

    /** Search in flight. */
    SEARCH_PROGRESS,

    /** Search error banner. */
    SEARCH_ERROR,

    /** No source returned anything for the query. */
    SEARCH_EMPTY,

    /** One source's header above its slice of results. */
    SOURCE_HEADER,

    /** One source's slice failed to load. */
    SOURCE_ERROR,

    /** One source's slice returned nothing. */
    SOURCE_EMPTY,
}

/**
 * The whole structure of the discover grid: the structural entries before the comics, the sections
 * of comics, and the structural entries after them.
 *
 * Both the scroll-anchor mapper and the grid renderer read this, so "where does comic N sit" has one
 * answer. Pure, so a JVM test can drive every state without Compose.
 */
data class DiscoverGridLayout(
    val leading: List<DiscoverStructuralItem>,
    val sections: List<DiscoverGridComicSection>,
    val trailing: List<DiscoverStructuralItem>,
) {
    val comicItems: List<DiscoverGridComicItem> by lazy {
        mapDiscoverGridComicItems(
            leadingStructuralItemCount = leading.size,
            sections = sections,
            trailingStructuralItemCount = trailing.size,
        )
    }

    /** True when at least one section carries a comic, i.e. there is something to anchor to. */
    val hasComics: Boolean
        get() = sections.any { it.comicKeys.isNotEmpty() }
}

/** Everything the browse grid's structure depends on, and nothing else. */
data class DiscoverBrowseGridInput(
    val feedCount: Int,
    val hasSelectedFeed: Boolean,
    val isLoadingFeeds: Boolean,
    val hasFeedLoadError: Boolean,
    val hasBrowseError: Boolean,
    val isBrowsing: Boolean,
    val hasMore: Boolean,
)

/**
 * The browse grid's layout. Comic identity is passed in separately so this stays free of the
 * ViewModel and the plugin wire types, which is what makes it testable.
 */
fun discoverBrowseGridLayout(
    input: DiscoverBrowseGridInput,
    comicKeys: List<DiscoverComicKey>,
): DiscoverGridLayout {
    // The first feed list is still loading, or the surface has nothing to show yet at all.
    if (input.feedCount == 0) {
        val leading =
            if (input.isLoadingFeeds) {
                listOf(DiscoverStructuralItem.FEED_LOADING)
            } else if (input.hasFeedLoadError) {
                listOf(DiscoverStructuralItem.FEED_EMPTY_ERROR)
            } else {
                listOf(DiscoverStructuralItem.FEED_EMPTY_TEXT)
            }
        return DiscoverGridLayout(leading = leading, sections = emptyList(), trailing = emptyList())
    }
    if (!input.hasSelectedFeed) {
        return DiscoverGridLayout(leading = emptyList(), sections = emptyList(), trailing = emptyList())
    }

    val leading = buildList {
        if (input.hasFeedLoadError) add(DiscoverStructuralItem.FEED_ERROR)
        if (input.hasBrowseError) add(DiscoverStructuralItem.BROWSE_ERROR)
        if (comicKeys.isEmpty() && (input.isBrowsing || !input.hasBrowseError)) {
            add(
                if (input.isBrowsing) {
                    DiscoverStructuralItem.BROWSE_PROGRESS
                } else {
                    DiscoverStructuralItem.BROWSE_EMPTY
                }
            )
        }
    }
    val trailing =
        when {
            input.isBrowsing && comicKeys.isNotEmpty() -> listOf(DiscoverStructuralItem.LOAD_MORE)
            input.hasMore && input.hasBrowseError -> listOf(DiscoverStructuralItem.LOAD_MORE)
            else -> emptyList()
        }
    return DiscoverGridLayout(
        leading = leading,
        sections =
            listOf(
                DiscoverGridComicSection(
                    structuralItemCount = 0,
                    comicKeys = comicKeys,
                )
            ),
        trailing = trailing,
    )
}

/** One source's slice of a search result page. */
data class DiscoverSearchGroupInput(
    val comicKeys: List<DiscoverComicKey>,
    val hasError: Boolean,
)

/** Everything the search grid's structure depends on, and nothing else. */
data class DiscoverSearchGridInput(
    val query: String,
    val isSearching: Boolean,
    val hasError: Boolean,
    val groups: List<DiscoverSearchGroupInput>,
)

/**
 * True when no source returned anything for [input]. This used to live inline in a Composable, which
 * changed the index of every comic below it while being invisible to tests.
 */
fun discoverSearchHasNoResults(input: DiscoverSearchGridInput): Boolean =
    input.groups.isEmpty() || input.groups.all { !it.hasError && it.comicKeys.isEmpty() }

fun discoverSearchGridLayout(input: DiscoverSearchGridInput): DiscoverGridLayout {
    val leading = buildList {
        if (input.isSearching) add(DiscoverStructuralItem.SEARCH_PROGRESS)
        if (input.hasError) add(DiscoverStructuralItem.SEARCH_ERROR)
    }
    if (!input.isSearching && !input.hasError && input.query.isNotBlank() && discoverSearchHasNoResults(input)) {
        return DiscoverGridLayout(
            leading = leading + DiscoverStructuralItem.SEARCH_EMPTY,
            sections = emptyList(),
            trailing = emptyList(),
        )
    }
    return DiscoverGridLayout(
        leading = leading,
        sections =
            input.groups.map { group ->
                val structural =
                    buildList {
                        add(DiscoverStructuralItem.SOURCE_HEADER)
                        when {
                            group.hasError -> add(DiscoverStructuralItem.SOURCE_ERROR)
                            group.comicKeys.isEmpty() -> add(DiscoverStructuralItem.SOURCE_EMPTY)
                        }
                    }
                DiscoverGridComicSection(
                    structuralItemCount = structural.size,
                    comicKeys = if (group.hasError) emptyList() else group.comicKeys,
                    structuralItems = structural,
                )
            },
        trailing = emptyList(),
    )
}
