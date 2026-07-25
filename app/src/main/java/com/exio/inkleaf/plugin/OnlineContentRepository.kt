package com.exio.inkleaf.plugin

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OnlineContentKey(val pluginId: String, val sourceId: String)

@Serializable
enum class OnlineAvailability {
    AVAILABLE,
    PLUGIN_DISABLED,
    PLUGIN_UNINSTALLED,
    PLUGIN_INCOMPATIBLE,
    AUTH_REQUIRED,
    CONTENT_MISSING,
    TEMPORARY_ERROR,
}

@Serializable
enum class OnlineUserReference {
    FAVORITE,
    HISTORY,
    BOOKMARK,
    PAGE_FAVORITE,
}

@Serializable
data class OnlineReadingPosition(
    val chapterId: String,
    val pageId: String? = null,
    val pageIndex: Int,
    val chapterRevision: String? = null,
    val updatedAtMs: Long,
)

@Serializable
data class OnlineComicRecord(
    val key: OnlineContentKey,
    val detail: ComicDetail? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val chaptersRevision: String? = null,
    val position: OnlineReadingPosition? = null,
    val availability: OnlineAvailability = OnlineAvailability.AVAILABLE,
    val references: Set<OnlineUserReference> = emptySet(),
    val lastSeenAtMs: Long = 0L,
)

@Serializable
private data class OnlineContentState(val records: List<OnlineComicRecord> = emptyList())

/**
 * Source-aware online metadata snapshot store. Page bytes and temporary image URLs are never owned
 * by this repository.
 */
class OnlineContentRepository(
    private val file: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val json: Json = defaultJson,
) {
    private val lock = Any()

    init {
        require(file.isAbsolute) { "Online content state path must be absolute" }
    }

    fun get(pluginId: String, sourceId: String): OnlineComicRecord? = synchronized(lock) {
        read().records.firstOrNull { it.key == key(pluginId, sourceId) }
    }

    fun list(): List<OnlineComicRecord> = synchronized(lock) { read().records }

    fun recordDetail(pluginId: String, detail: ComicDetail): OnlineComicRecord = synchronized(lock) {
        val key = key(pluginId, detail.sourceId)
        update(key) { current ->
            current.copy(
                detail = detail,
                availability = OnlineAvailability.AVAILABLE,
                lastSeenAtMs = clockMs(),
            )
        }
    }

    fun recordChapters(pluginId: String, response: PluginChaptersResponse): OnlineComicRecord = synchronized(lock) {
        val key = key(pluginId, response.sourceId)
        update(key) { current ->
            current.copy(
                chapters = response.chapters,
                chaptersRevision = response.revision,
                availability = OnlineAvailability.AVAILABLE,
                lastSeenAtMs = clockMs(),
            )
        }
    }

    fun recordPosition(
        pluginId: String,
        sourceId: String,
        chapterId: String,
        pageId: String?,
        pageIndex: Int,
        chapterRevision: String?,
    ): OnlineComicRecord = synchronized(lock) {
        require(pageIndex >= 0) { "pageIndex must be non-negative" }
        val key = key(pluginId, sourceId)
        update(key) { current ->
            current.copy(
                position = OnlineReadingPosition(
                    chapterId = chapterId,
                    pageId = pageId,
                    pageIndex = pageIndex,
                    chapterRevision = chapterRevision,
                    updatedAtMs = clockMs(),
                ),
                references = current.references + OnlineUserReference.HISTORY,
                lastSeenAtMs = clockMs(),
            )
        }
    }

    fun setAvailability(
        pluginId: String,
        sourceId: String,
        availability: OnlineAvailability,
    ): OnlineComicRecord = synchronized(lock) {
        val key = key(pluginId, sourceId)
        update(key) { current -> current.copy(availability = availability) }
    }

    fun setPluginAvailability(pluginId: String, availability: OnlineAvailability) = synchronized(lock) {
        require(PluginIds.isValid(pluginId)) { "Invalid plugin id" }
        val state = read()
        val next = state.copy(
            records = state.records.map { record ->
                if (record.key.pluginId == pluginId) record.copy(availability = availability) else record
            }
        )
        write(next)
    }

    fun setReference(
        pluginId: String,
        sourceId: String,
        reference: OnlineUserReference,
        present: Boolean,
    ): OnlineComicRecord = synchronized(lock) {
        val key = key(pluginId, sourceId)
        update(key) { current ->
            current.copy(
                references = if (present) current.references + reference else current.references - reference,
            )
        }
    }

    private fun update(
        key: OnlineContentKey,
        transform: (OnlineComicRecord) -> OnlineComicRecord,
    ): OnlineComicRecord {
        val state = read()
        val current = state.records.firstOrNull { it.key == key } ?: OnlineComicRecord(key)
        val updated = transform(current)
        val records = state.records.filterNot { it.key == key } + updated
        write(OnlineContentState(records.sortedWith(compareBy({ it.key.pluginId }, { it.key.sourceId }))))
        return updated
    }

    private fun key(pluginId: String, sourceId: String): OnlineContentKey {
        require(PluginIds.isValid(pluginId)) { "Invalid plugin id" }
        require(sourceId.isNotBlank() && sourceId.length <= 512) { "Invalid source id" }
        return OnlineContentKey(pluginId, sourceId)
    }

    private fun read(): OnlineContentState {
        if (!file.isFile) return OnlineContentState()
        return runCatching {
            json.decodeFromString<OnlineContentState>(file.readText(StandardCharsets.UTF_8))
        }.getOrElse { throw IOException("Online content state is corrupt", it) }
    }

    private fun write(state: OnlineContentState) {
        file.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Unable to create online content directory")
        }
        val temp = file.resolveSibling("${file.name}.tmp-${UUID.randomUUID()}")
        try {
            temp.writeText(json.encodeToString(OnlineContentState.serializer(), state), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        val defaultJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
