package com.exio.inkleaf.ui

import com.exio.inkleaf.data.PageRenderRequest

/**
 * Separates changes that invalidate the displayed page from changes that only refresh its processing.
 */
internal data class ReaderPageContentKeys(
    val stateReset: StateReset,
    val producerRestart: ProducerRestart,
) {
    internal data class StateReset(
        val volumeToken: Any,
        val page: Int,
        val cacheKeyPrefix: String,
    )

    internal data class ProducerRestart(
        val isCurrentPage: Boolean,
        val pageRenderRequest: PageRenderRequest?,
        val enhancementSelectionId: String,
        val enhancementModelInstalled: Boolean,
        val pinForActiveTask: Boolean,
    )
}

internal fun readerPageContentKeys(
    volumeToken: Any,
    page: Int,
    cacheKeyPrefix: String,
    isCurrentPage: Boolean,
    pageRenderRequest: PageRenderRequest?,
    enhancementSelectionId: String,
    enhancementModelInstalled: Boolean,
    pinForActiveTask: Boolean,
): ReaderPageContentKeys = ReaderPageContentKeys(
    stateReset = ReaderPageContentKeys.StateReset(
        volumeToken = volumeToken,
        page = page,
        cacheKeyPrefix = cacheKeyPrefix,
    ),
    producerRestart = ReaderPageContentKeys.ProducerRestart(
        isCurrentPage = isCurrentPage,
        pageRenderRequest = pageRenderRequest,
        enhancementSelectionId = enhancementSelectionId,
        enhancementModelInstalled = enhancementModelInstalled,
        pinForActiveTask = pinForActiveTask,
    ),
)
