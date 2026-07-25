// Immutable reader selection state keeps navigation and back handling from clearing only part of
// OCR UI state.
package com.exio.inkleaf.data.ocr

data class OcrSelectionSession(
    val activePage: Int? = null,
    val selectedIds: Set<Int> = emptySet(),
    val detailText: String? = null,
) {
    fun enter(page: Int): OcrSelectionSession = OcrSelectionSession(activePage = page)

    fun toggle(regionId: Int): OcrSelectionSession {
        val updated = LinkedHashSet(selectedIds)
        if (!updated.add(regionId)) updated.remove(regionId)
        return copy(selectedIds = updated, detailText = null)
    }

    fun add(regionId: Int): OcrSelectionSession {
        if (regionId in selectedIds) return this
        return copy(
            selectedIds = LinkedHashSet(selectedIds).apply { add(regionId) },
            detailText = null,
        )
    }

    fun clearSelection(): OcrSelectionSession = copy(selectedIds = emptySet(), detailText = null)

    fun showText(text: String): OcrSelectionSession = copy(detailText = text)

    fun dismissText(): OcrSelectionSession = copy(detailText = null)

    fun exit(): OcrSelectionSession = OcrSelectionSession()

    fun onPageChanged(page: Int): OcrSelectionSession = if (activePage == page) this else exit()
}
