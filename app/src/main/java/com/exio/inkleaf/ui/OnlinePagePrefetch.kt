package com.exio.inkleaf.ui

/**
 * Returns up to [count] page indices after [currentPage], ordered by distance.
 *
 * A negative [direction] reads backward; any other value reads forward. [count] is the
 * maximum number of pages requested: the result is shorter near a chapter boundary and
 * empty when [currentPage] is outside `0 until pageCount` or [count] is not positive.
 * [OnlineReaderViewModel] derives [count] from the current network type.
 */
internal fun onlinePagePrefetchOrder(
    currentPage: Int,
    pageCount: Int,
    direction: Int,
    count: Int,
): List<Int> {
    if (currentPage !in 0 until pageCount || count <= 0) return emptyList()
    val step = if (direction < 0) -1 else 1
    return (1..count).map { currentPage + step * it }.filter { it in 0 until pageCount }
}

/**
 * Returns the entry pages of an adjacent chapter (the first two pages when reading forward,
 * the last two when reading backward), clipped to the chapter's page count.
 */
internal fun adjacentOnlinePagePrefetchOrder(pageCount: Int, direction: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val lastPage = pageCount - 1
    return if (direction < 0) {
        listOf(lastPage, lastPage - 1).filter { it >= 0 }
    } else {
        (0..minOf(1, lastPage)).toList()
    }
}
