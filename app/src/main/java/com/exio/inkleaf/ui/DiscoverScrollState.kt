package com.exio.inkleaf.ui

import kotlin.math.abs

/** The plugin identity that participates in a search scroll context. */
data class DiscoverSearchPluginKey(
    val pluginId: String,
    val pluginVersion: String,
)

/** Identity of the list whose scroll position is being retained. */
sealed interface DiscoverScrollContextKey {
    data class Browse(
        val pluginId: String,
        val pluginVersion: String,
        val feedId: String,
        val filters: Map<String, String>,
    ) : DiscoverScrollContextKey

    data class Search(
        val query: String,
        val selectedPlugins: Set<DiscoverSearchPluginKey>,
    ) : DiscoverScrollContextKey
}

/** Stable identity of a comic item within the discovery surface. */
data class DiscoverComicKey(
    val pluginId: String,
    val sourceId: String,
)

data class DiscoverGridComicItem(
    val key: DiscoverComicKey,
    val gridIndex: Int,
)

/**
 * A section of lazy-grid content. Structural items include headers, errors, progress indicators,
 * and empty states; comic items are the only entries that can become scroll anchors.
 */
data class DiscoverGridComicSection(
    val structuralItemCount: Int,
    val comicKeys: List<DiscoverComicKey>,
) {
    init {
        require(structuralItemCount >= 0) { "structuralItemCount must be non-negative" }
    }
}

/** Maps the logical comic order to the actual LazyGrid item indexes. */
fun mapDiscoverGridComicItems(
    leadingStructuralItemCount: Int,
    sections: List<DiscoverGridComicSection>,
    trailingStructuralItemCount: Int = 0,
): List<DiscoverGridComicItem> {
    require(leadingStructuralItemCount >= 0) {
        "leadingStructuralItemCount must be non-negative"
    }
    require(trailingStructuralItemCount >= 0) {
        "trailingStructuralItemCount must be non-negative"
    }

    var gridIndex = leadingStructuralItemCount
    return buildList {
        sections.forEach { section ->
            gridIndex += section.structuralItemCount
            section.comicKeys.forEach { key ->
                add(DiscoverGridComicItem(key = key, gridIndex = gridIndex))
                gridIndex += 1
            }
        }
        gridIndex += trailingStructuralItemCount
    }
}

data class DiscoverScrollAnchor(
    val itemKey: DiscoverComicKey,
    val scrollOffset: Int,
    val orderedKeys: List<DiscoverComicKey>,
)

internal class DiscoverSearchRequestGate {
    private var generation = 0L

    fun next(): Long = ++generation

    fun invalidate() {
        generation += 1
    }

    fun accepts(candidate: Long): Boolean = candidate == generation
}

data class DiscoverScrollTarget(
    val gridIndex: Int,
    val scrollOffset: Int,
)

/**
 * Resolves an anchor against the current content. Exact identity wins; if it disappeared, the
 * nearest surviving item in the old logical order is used, preferring the successor on a tie.
 */
fun resolveDiscoverScrollTarget(
    anchor: DiscoverScrollAnchor?,
    currentItems: List<DiscoverGridComicItem>,
): DiscoverScrollTarget? {
    if (anchor == null || currentItems.isEmpty()) return null

    currentItems
        .firstOrNull { it.key == anchor.itemKey }
        ?.let { exact ->
            return DiscoverScrollTarget(exact.gridIndex, anchor.scrollOffset)
        }

    val oldIndex = anchor.orderedKeys.indexOf(anchor.itemKey)
    if (oldIndex < 0) return null
    val oldPositions = anchor.orderedKeys.withIndex().associate { it.value to it.index }

    val nearest =
        currentItems.withIndex()
            .filter { (_, item) -> item.key in oldPositions }
            .minWithOrNull(
                compareBy<IndexedValue<DiscoverGridComicItem>>(
                    { abs(oldPositions.getValue(it.value.key) - oldIndex) },
                    {
                        if (oldPositions.getValue(it.value.key) >= oldIndex) 0 else 1
                    },
                    { oldPositions.getValue(it.value.key) },
                    { it.index },
                )
            ) ?: return null

    return DiscoverScrollTarget(nearest.value.gridIndex, anchor.scrollOffset)
}

/** Access-ordered in-memory retention for the current navigation entry. */
class DiscoverScrollAnchorStore(private val maxEntries: Int = 32) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val anchors =
        object : LinkedHashMap<DiscoverScrollContextKey, DiscoverScrollAnchor>(
            maxEntries,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<DiscoverScrollContextKey, DiscoverScrollAnchor>?
            ): Boolean = size > maxEntries
        }

    fun get(contextKey: DiscoverScrollContextKey): DiscoverScrollAnchor? = anchors[contextKey]

    fun put(contextKey: DiscoverScrollContextKey, anchor: DiscoverScrollAnchor) {
        anchors[contextKey] = anchor
    }
}
