package com.exio.inkleaf.ui

internal fun thumbnailPrewarmOrder(
    currentPage: Int,
    pageCount: Int,
    radius: Int,
): List<Int> {
    if (pageCount <= 0 || radius < 0 || currentPage !in 0 until pageCount) return emptyList()
    return buildList {
        add(currentPage)
        for (distance in 1..radius) {
            (currentPage + distance).takeIf { it < pageCount }?.let(::add)
            (currentPage - distance).takeIf { it >= 0 }?.let(::add)
        }
    }
}
