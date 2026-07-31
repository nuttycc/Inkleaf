package com.exio.inkleaf.ui

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

internal fun adjacentOnlinePagePrefetchOrder(pageCount: Int, direction: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val lastPage = pageCount - 1
    return if (direction < 0) {
        listOf(lastPage, lastPage - 1).filter { it >= 0 }
    } else {
        (0..minOf(1, lastPage)).toList()
    }
}
