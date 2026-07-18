package com.exio.inkleaf.data.db

internal data class EnhancementCacheProgress(
    val completedPages: Int,
    val nextPage: Int,
    val isComplete: Boolean,
)

internal fun calculateEnhancementCacheProgress(
    startPageInclusive: Int,
    endPageInclusive: Int,
    completedPages: Collection<Int>,
): EnhancementCacheProgress {
    require(startPageInclusive >= 0)
    require(endPageInclusive >= startPageInclusive)
    val completedInRange = completedPages
        .asSequence()
        .filter { it in startPageInclusive..endPageInclusive }
        .toSet()
    var nextPage = startPageInclusive
    while (nextPage <= endPageInclusive && nextPage in completedInRange) nextPage++
    val totalPages = endPageInclusive - startPageInclusive + 1
    return EnhancementCacheProgress(
        completedPages = completedInRange.size,
        nextPage = nextPage,
        isComplete = completedInRange.size == totalPages,
    )
}
