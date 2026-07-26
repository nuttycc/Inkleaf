package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

typealias OnlineContentKey = OnlineContentIdentity

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
    /** Legacy name for the comic-level follow used by the current online detail screen. */
    BOOKMARK,
    /** Legacy summary flag. New page favorites are stored as explicit snapshot records. */
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

/** A lightweight location record. It never contains image bytes or remote image URLs. */
@Serializable
data class OnlinePageBookmark(
    val location: OnlinePageLocation,
    val chapterTitleSnapshot: String? = null,
    val addedAtMs: Long,
)

@Serializable
data class OnlinePageSnapshotMetadata(
    val relativePath: String,
    val mimeType: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val writtenAtMs: Long,
) {
    init {
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
        require(byteCount >= 0) { "byteCount must be non-negative" }
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }
}

@Serializable
data class OnlinePageFavorite(
    val location: OnlinePageLocation,
    val chapterTitleSnapshot: String? = null,
    val snapshot: OnlinePageSnapshotMetadata,
    val addedAtMs: Long,
)

/** A completed online reading session retained as history independently of plugin availability. */
@Serializable
data class OnlineReadingSessionRecord(
    val sessionId: String,
    val content: OnlineContentIdentity,
    val titleSnapshot: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val activeReadingMillis: Long,
    val timeZoneId: String,
    val start: OnlinePageLocation,
    val end: OnlinePageLocation,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(endedAtMs >= startedAtMs) { "endedAtMs must not precede startedAtMs" }
        require(activeReadingMillis >= 0) { "activeReadingMillis must be non-negative" }
        require(timeZoneId.isNotBlank()) { "timeZoneId must not be blank" }
        require(start.identity.chapter.content == content) { "Start location must match content" }
        require(end.identity.chapter.content == content) { "End location must match content" }
    }
}

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
    val pageBookmarks: List<OnlinePageBookmark> = emptyList(),
    val pageFavorites: List<OnlinePageFavorite> = emptyList(),
    val readingSessions: List<OnlineReadingSessionRecord> = emptyList(),
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
    private val snapshotDirectory = file.resolveSibling("page-favorites")

    init {
        require(file.isAbsolute) { "Online content state path must be absolute" }
    }

    fun get(pluginId: String, sourceId: String): OnlineComicRecord? = synchronized(lock) {
        read().records.firstOrNull { it.key == key(pluginId, sourceId) }
    }

    fun list(): List<OnlineComicRecord> = synchronized(lock) { read().records }

    fun listPageBookmarks(): List<OnlinePageBookmark> = synchronized(lock) {
        read().records.flatMap { it.pageBookmarks }.sortedByDescending { it.addedAtMs }
    }

    fun addPageBookmark(
        location: OnlinePageLocation,
        chapterTitleSnapshot: String? = null,
    ): OnlinePageBookmark = synchronized(lock) {
        val contentKey = key(location.identity.chapter.content)
        val bookmark = OnlinePageBookmark(
            location = location,
            chapterTitleSnapshot = chapterTitleSnapshot,
            addedAtMs = clockMs(),
        )
        update(contentKey) { current ->
            current.copy(
                pageBookmarks = (current.pageBookmarks.filterNot {
                    it.location.identity == location.identity
                } + bookmark).sortedByDescending { it.addedAtMs },
            )
        }
        bookmark
    }

    fun removePageBookmark(identity: OnlinePageIdentity): Boolean = synchronized(lock) {
        removeUserRecord(key(identity.chapter.content)) { current ->
            val next = current.pageBookmarks.filterNot { it.location.identity == identity }
            current.copy(pageBookmarks = next) to (next.size != current.pageBookmarks.size)
        }
    }

    fun listPageFavorites(): List<OnlinePageFavorite> = synchronized(lock) {
        read().records.flatMap { it.pageFavorites }.sortedByDescending { it.addedAtMs }
    }

    /**
     * Allocates a unique final file under the repository's app-private directory. The caller must
     * write the snapshot atomically before calling [recordPageFavoriteSnapshot].
     */
    fun pageFavoriteSnapshotFile(identity: OnlinePageIdentity, extension: String): File {
        val normalizedExtension = extension.lowercase()
        require(FILE_EXTENSION.matches(normalizedExtension)) { "Invalid snapshot file extension" }
        ensureDirectory(snapshotDirectory)
        val identityKey = snapshotStorageKey(identity)
        while (true) {
            val candidate = snapshotDirectory.resolve(
                "$identityKey-${UUID.randomUUID()}.$normalizedExtension"
            )
            if (!candidate.exists()) return candidate
        }
    }

    /** Atomically publishes snapshot metadata after the durable snapshot file exists. */
    fun recordPageFavoriteSnapshot(
        location: OnlinePageLocation,
        snapshotFile: File,
        mimeType: String,
        width: Int,
        height: Int,
        chapterTitleSnapshot: String? = null,
    ): OnlinePageFavorite = synchronized(lock) {
        val relativePath = requireStoredSnapshot(snapshotFile, location.identity)
        val now = clockMs()
        val favorite = OnlinePageFavorite(
            location = location,
            chapterTitleSnapshot = chapterTitleSnapshot,
            snapshot = OnlinePageSnapshotMetadata(
                relativePath = relativePath,
                mimeType = mimeType,
                byteCount = snapshotFile.length(),
                width = width,
                height = height,
                writtenAtMs = now,
            ),
            addedAtMs = now,
        )
        var previousSnapshotPath: String? = null
        val published = update(key(location.identity.chapter.content)) { current ->
            val previous = current.pageFavorites.firstOrNull {
                it.location.identity == location.identity
            }
            previousSnapshotPath = previous?.snapshot?.relativePath
            require(previousSnapshotPath != relativePath) {
                "Page favorite replacement requires a newly allocated snapshot file"
            }
            val stored = if (previous == null) favorite else favorite.copy(addedAtMs = previous.addedAtMs)
            current.copy(
                pageFavorites = (current.pageFavorites.filterNot {
                    it.location.identity == location.identity
                } + stored).sortedByDescending { it.addedAtMs },
            )
        }.pageFavorites.first { it.location.identity == location.identity }
        previousSnapshotPath
            ?.takeIf { it != published.snapshot.relativePath }
            ?.let { oldPath -> runCatching { resolveStoredSnapshot(oldPath).delete() } }
        published
    }

    fun resolvePageFavoriteSnapshot(favorite: OnlinePageFavorite): File =
        resolveStoredSnapshot(favorite.snapshot.relativePath)

    fun removePageFavorite(identity: OnlinePageIdentity): Boolean = synchronized(lock) {
        val contentKey = key(identity.chapter.content)
        val state = read()
        val current = state.records.firstOrNull { it.key == contentKey } ?: return false
        val removed = current.pageFavorites.firstOrNull { it.location.identity == identity }
            ?: return false
        val updated = current.copy(
            pageFavorites = current.pageFavorites.filterNot { it.location.identity == identity }
        )
        val records = state.records.filterNot { it.key == contentKey } + updated
        write(OnlineContentState(records.sortedWith(compareBy({ it.key.pluginId }, { it.key.sourceId }))))
        // Metadata is removed first. A crash can leave an orphan file, but never a broken favorite.
        runCatching { resolveStoredSnapshot(removed.snapshot.relativePath).delete() }
        true
    }

    fun listReadingSessions(): List<OnlineReadingSessionRecord> = synchronized(lock) {
        read().records.flatMap { it.readingSessions }
            .sortedWith(compareByDescending<OnlineReadingSessionRecord> { it.endedAtMs }.thenByDescending { it.sessionId })
    }

    fun recordReadingSession(session: OnlineReadingSessionRecord): OnlineReadingSessionRecord = synchronized(lock) {
        update(key(session.content)) { current ->
            current.copy(
                readingSessions = (current.readingSessions.filterNot {
                    it.sessionId == session.sessionId
                } + session).sortedWith(
                    compareByDescending<OnlineReadingSessionRecord> { it.endedAtMs }
                        .thenByDescending { it.sessionId }
                ),
                references = current.references + OnlineUserReference.HISTORY,
            )
        }
        session
    }

    fun removeReadingSession(content: OnlineContentIdentity, sessionId: String): Boolean = synchronized(lock) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        removeUserRecord(key(content)) { current ->
            val next = current.readingSessions.filterNot { it.sessionId == sessionId }
            current.copy(readingSessions = next) to (next.size != current.readingSessions.size)
        }
    }

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
        val key = key(pluginId, sourceId)
        OnlinePageLocation.create(
            chapter = OnlineChapterIdentity(key, chapterId),
            pageId = pageId,
            pageIndex = pageIndex,
            chapterRevision = chapterRevision,
        )
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

    /** Comic-level follow state. This never adds or removes a page bookmark. */
    fun setComicFollow(pluginId: String, sourceId: String, present: Boolean): OnlineComicRecord =
        setReference(pluginId, sourceId, OnlineUserReference.BOOKMARK, present)

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

    private fun removeUserRecord(
        key: OnlineContentKey,
        transform: (OnlineComicRecord) -> Pair<OnlineComicRecord, Boolean>,
    ): Boolean {
        val state = read()
        val current = state.records.firstOrNull { it.key == key } ?: return false
        val (updated, changed) = transform(current)
        if (!changed) return false
        val records = state.records.filterNot { it.key == key } + updated
        write(OnlineContentState(records.sortedWith(compareBy({ it.key.pluginId }, { it.key.sourceId }))))
        return true
    }

    private fun key(identity: OnlineContentIdentity): OnlineContentKey =
        key(identity.pluginId, identity.sourceId)

    private fun key(pluginId: String, sourceId: String): OnlineContentKey {
        require(PluginIds.isValid(pluginId)) { "Invalid plugin id" }
        require(sourceId.isNotBlank() && sourceId.length <= 512) { "Invalid source id" }
        return OnlineContentKey(pluginId, sourceId)
    }

    private fun requireStoredSnapshot(
        snapshotFile: File,
        identity: OnlinePageIdentity,
    ): String {
        val canonicalDirectory = snapshotDirectory.canonicalFile
        val canonicalSnapshot = snapshotFile.canonicalFile
        require(canonicalSnapshot.isFile) { "Snapshot file must exist before metadata is recorded" }
        require(canonicalSnapshot.parentFile == canonicalDirectory) {
            "Snapshot file must be inside the page favorite directory"
        }
        val fileMatch = ALLOCATED_SNAPSHOT_FILE.matchEntire(canonicalSnapshot.name)
        require(fileMatch?.groupValues?.get(1) == snapshotStorageKey(identity)) {
            "Snapshot file was not allocated for this page identity"
        }
        val contentDirectory = requireNotNull(file.parentFile).canonicalFile
        return contentDirectory.toPath()
            .relativize(canonicalSnapshot.toPath())
            .toString()
            .replace(File.separatorChar, '/')
    }

    private fun resolveStoredSnapshot(relativePath: String): File {
        val contentDirectory = requireNotNull(file.parentFile).canonicalFile
        val snapshot = contentDirectory.resolve(relativePath.replace('/', File.separatorChar)).canonicalFile
        require(snapshot.toPath().startsWith(snapshotDirectory.canonicalFile.toPath())) {
            "Snapshot metadata escapes the page favorite directory"
        }
        return snapshot
    }

    private fun snapshotStorageKey(identity: OnlinePageIdentity): String {
        val fallback = identity.fallback
        val parts = listOf(
            identity.chapter.content.pluginId,
            identity.chapter.content.sourceId,
            identity.chapter.chapterId,
            if (identity.pageId != null) "id" else "revision-index",
            identity.pageId.orEmpty(),
            fallback?.chapterRevision.orEmpty(),
            fallback?.pageIndex?.toString().orEmpty(),
        )
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun read(): OnlineContentState {
        if (!file.isFile) return OnlineContentState()
        return runCatching {
            json.decodeFromString<OnlineContentState>(file.readText(StandardCharsets.UTF_8))
        }.getOrElse { throw IOException("Online content state is corrupt", it) }
    }

    private fun write(state: OnlineContentState) {
        file.parentFile?.let(::ensureDirectory)
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
        val FILE_EXTENSION = Regex("[a-z0-9]{1,8}")
        val ALLOCATED_SNAPSHOT_FILE = Regex(
            "([0-9a-f]{64})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.([a-z0-9]{1,8})"
        )

        fun ensureDirectory(directory: File) {
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Unable to create online content directory")
            }
        }

        val defaultJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
