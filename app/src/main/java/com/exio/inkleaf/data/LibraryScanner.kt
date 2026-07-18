package com.exio.inkleaf.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Enumerates user-granted SAF trees without touching the database.
 *
 * Directory queries stay serial because document providers may be remote, Binder-backed, or both.
 * The scanner exposes completeness and limit state so callers never interpret a truncated tree as
 * authoritative deletion evidence.
 */
class LibraryScanner(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class ScannedFile(
        val uri: String,
        val fileKey: String,
        val displayName: String,
        val relativePath: String = displayName,
        val mimeType: String? = null,
        val size: Long? = null,
        val lastModified: Long? = null,
        val flags: Int = 0,
    )

    data class ScanThresholds(
        val pdfCount: Int,
        val directoryCount: Int,
        val entryCount: Int,
    )

    data class ScanMetrics(
        val pdfCount: Int = 0,
        val directoryCount: Int = 1,
        val entryCount: Int = 0,
    )

    enum class ScanLimit {
        PDFS,
        DIRECTORIES,
        ENTRIES,
        DEPTH,
    }

    enum class ScanStopReason {
        CONFIRMATION_REQUIRED,
        HARD_LIMIT_REACHED,
    }

    data class PdfScanResult(
        val files: List<ScannedFile>,
        val metrics: ScanMetrics,
        val inaccessibleDirectoryCount: Int,
        val skippedVirtualPdfCount: Int = 0,
        val stopReason: ScanStopReason? = null,
        val limit: ScanLimit? = null,
    ) {
        val isComplete: Boolean
            get() = inaccessibleDirectoryCount == 0 && stopReason == null
    }

    /** The selected root itself is inaccessible or no longer granted. */
    class FolderAccessException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Recursively lists archive comics. This retains the existing archive-library semantics while
     * sharing the cancellable, one-query-per-directory traversal used by PDF scanning.
     */
    suspend fun scanFolder(treeUri: Uri): List<ScannedFile> = withContext(Dispatchers.IO) {
        val found = mutableListOf<ScannedFile>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<PendingDirectory>()
        val visitedDirectories = mutableSetOf(documentVisitKey(treeUri, rootDocId))
        queue.add(PendingDirectory(rootDocId, relativePath = "", depth = 0))

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = queue.removeFirst()
            try {
                visitChildren(treeUri, directory.docId) { child ->
                    if (child.name.startsWith(".")) return@visitChildren true
                    val relativePath = joinRelativePath(directory.relativePath, child.name)
                    if (child.isDirectory) {
                        if (directory.depth < MAX_DEPTH) {
                            val visitKey = documentVisitKey(treeUri, child.docId)
                            if (visitedDirectories.add(visitKey)) {
                                queue.add(
                                    PendingDirectory(
                                        docId = child.docId,
                                        relativePath = relativePath,
                                        depth = directory.depth + 1,
                                    )
                                )
                            }
                        }
                    } else if (COMIC_EXT.matches(child.name)) {
                        val fileUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
                        found.add(
                            child.toScannedFile(
                                treeUri = treeUri,
                                relativePath = relativePath,
                                fileKey = ComicIdentity.fileKey(appContext, fileUri),
                            )
                        )
                    }
                    true
                }
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                if (directory.depth == 0) {
                    throw FolderAccessException("无法访问该目录", error)
                }
            }
        }
        found
    }

    /**
     * Recursively scans every PDF below [treeUri]. Reaching a confirmation threshold stops before
     * database work; callers can repeat the metadata-only scan with a higher approved threshold.
     */
    suspend fun scanPdfsRecursively(
        treeUri: Uri,
        confirmationThresholds: ScanThresholds? = SOFT_SCAN_THRESHOLDS,
    ): PdfScanResult = withContext(Dispatchers.IO) {
        confirmationThresholds?.let(::requireThresholds)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<PendingDirectory>()
        val visitedDirectories = mutableSetOf(documentVisitKey(treeUri, rootDocId))
        val visitedFiles = mutableSetOf<String>()
        val found = mutableListOf<ScannedFile>()
        var metrics = ScanMetrics()
        var inaccessibleDirectories = 0
        var skippedVirtualPdfs = 0
        var stopReason: ScanStopReason? = null
        var stopLimit: ScanLimit? = null

        queue.add(PendingDirectory(rootDocId, relativePath = "", depth = 0))

        scan@ while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = queue.removeFirst()
            try {
                val providerStillLoading = visitChildren(treeUri, directory.docId) { child ->
                    metrics = metrics.copy(entryCount = metrics.entryCount + 1)
                    evaluateLimit(metrics, confirmationThresholds)?.let { decision ->
                        stopReason = decision.first
                        stopLimit = decision.second
                        return@visitChildren false
                    }

                    if (child.name.startsWith(".")) return@visitChildren true
                    val relativePath = joinRelativePath(directory.relativePath, child.name)
                    if (child.isDirectory) {
                        if (directory.depth >= MAX_DEPTH) {
                            stopReason = ScanStopReason.HARD_LIMIT_REACHED
                            stopLimit = ScanLimit.DEPTH
                            return@visitChildren false
                        }
                        val visitKey = documentVisitKey(treeUri, child.docId)
                        if (visitedDirectories.add(visitKey)) {
                            metrics = metrics.copy(directoryCount = metrics.directoryCount + 1)
                            evaluateLimit(metrics, confirmationThresholds)?.let { decision ->
                                stopReason = decision.first
                                stopLimit = decision.second
                                return@visitChildren false
                            }
                            queue.add(
                                PendingDirectory(
                                    docId = child.docId,
                                    relativePath = relativePath,
                                    depth = directory.depth + 1,
                                )
                            )
                        }
                    } else if (isPdf(child.name, child.mimeType)) {
                        if (
                            child.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                        ) {
                            skippedVirtualPdfs++
                            return@visitChildren true
                        }
                        val fileKey = ComicIdentity.safDocumentKey(treeUri.authority, child.docId)
                        if (visitedFiles.add(fileKey)) {
                            found.add(child.toScannedFile(treeUri, relativePath, fileKey))
                            metrics = metrics.copy(pdfCount = metrics.pdfCount + 1)
                            evaluateLimit(metrics, confirmationThresholds)?.let { decision ->
                                stopReason = decision.first
                                stopLimit = decision.second
                                return@visitChildren false
                            }
                        }
                    }
                    true
                }
                if (providerStillLoading) inaccessibleDirectories++
                if (stopReason != null) break@scan
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                if (directory.depth == 0) {
                    throw FolderAccessException("无法访问该目录", error)
                }
                inaccessibleDirectories++
            }
        }

        PdfScanResult(
            files = sortPdfs(found),
            metrics = metrics,
            inaccessibleDirectoryCount = inaccessibleDirectories,
            skippedVirtualPdfCount = skippedVirtualPdfs,
            stopReason = stopReason,
            limit = stopLimit,
        )
    }

    /** Compatibility path for old callers. New synchronization code must inspect structured state. */
    suspend fun scanPdfs(treeUri: Uri): List<ScannedFile> {
        val result = scanPdfsRecursively(treeUri, confirmationThresholds = null)
        if (result.stopReason != null) {
            throw FolderAccessException("目录内容超过安全扫描上限")
        }
        return result.files
    }

    private data class PendingDirectory(
        val docId: String,
        val relativePath: String,
        val depth: Int,
    )

    private data class Child(
        val docId: String,
        val name: String,
        val mimeType: String?,
        val flags: Int,
        val size: Long?,
        val lastModified: Long?,
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

        fun toScannedFile(
            treeUri: Uri,
            relativePath: String,
            fileKey: String = ComicIdentity.safDocumentKey(treeUri.authority, docId),
        ): ScannedFile {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            return ScannedFile(
                uri = fileUri.toString(),
                fileKey = fileKey,
                displayName = name,
                relativePath = relativePath,
                mimeType = mimeType,
                size = size,
                lastModified = lastModified,
                flags = flags,
            )
        }
    }

    /** Returns true when the provider reports that this directory is still loading. */
    private suspend fun visitChildren(
        treeUri: Uri,
        parentDocId: String,
        visitor: (Child) -> Boolean,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val signal = CancellationSignal()
        val job = coroutineContext.job
        val cancellationHandle = job.invokeOnCompletion {
            if (job.isCancelled) signal.cancel()
        }
        try {
            val cursor = resolver.query(
                childrenUri,
                CHILD_PROJECTION,
                null,
                null,
                null,
                signal,
            ) ?: throw IllegalStateException("provider 返回了 null cursor")
            cursor.use {
                while (it.moveToNext()) {
                    coroutineContext.ensureActive()
                    val child = it.readChild() ?: continue
                    if (!visitor(child)) break
                }
                return runCatching {
                    it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                }.getOrDefault(false)
            }
        } finally {
            cancellationHandle.dispose()
        }
    }

    private fun Cursor.readChild(): Child? {
        val docId = getString(0) ?: return null
        val name = getString(1) ?: return null
        return Child(
            docId = docId,
            name = name,
            mimeType = getString(2),
            flags = if (isNull(3)) 0 else getInt(3),
            size = if (isNull(4)) null else getLong(4),
            lastModified = if (isNull(5)) null else getLong(5),
        )
    }

    companion object {
        private val COMIC_EXT = Regex(".*\\.(cbz|zip)$", RegexOption.IGNORE_CASE)
        private val PDF_EXT = Regex(".*\\.pdf$", RegexOption.IGNORE_CASE)

        val SOFT_SCAN_THRESHOLDS = ScanThresholds(
            pdfCount = 500,
            directoryCount = 1_000,
            entryCount = 10_000,
        )
        val HARD_SCAN_THRESHOLDS = ScanThresholds(
            pdfCount = 2_000,
            directoryCount = 5_000,
            entryCount = 50_000,
        )

        val COMIC_PICKER_MIME_TYPES = arrayOf(
            "application/zip",
            "application/x-cbz",
            "application/vnd.comicbook+zip",
            "application/octet-stream",
        )

        internal fun isPdf(displayName: String, mimeType: String?): Boolean =
            mimeType.equals("application/pdf", ignoreCase = true) || PDF_EXT.matches(displayName)

        internal fun sortPdfs(files: List<ScannedFile>): List<ScannedFile> =
            files.sortedWith { first, second ->
                ChapterSort.compareNatural(first.displayName, second.displayName)
                    .takeIf { it != 0 }
                    ?: ChapterSort.compareNatural(first.relativePath, second.relativePath)
                        .takeIf { it != 0 }
                    ?: first.fileKey.compareTo(second.fileKey)
            }

        internal fun evaluateLimit(
            metrics: ScanMetrics,
            confirmationThresholds: ScanThresholds?,
        ): Pair<ScanStopReason, ScanLimit>? {
            exceededHardLimit(metrics)?.let {
                return ScanStopReason.HARD_LIMIT_REACHED to it
            }
            confirmationThresholds?.let { thresholds ->
                reachedLimit(metrics, thresholds)?.let {
                    return ScanStopReason.CONFIRMATION_REQUIRED to it
                }
            }
            return null
        }

        private fun reachedLimit(metrics: ScanMetrics, limits: ScanThresholds): ScanLimit? = when {
            metrics.pdfCount >= limits.pdfCount -> ScanLimit.PDFS
            metrics.directoryCount >= limits.directoryCount -> ScanLimit.DIRECTORIES
            metrics.entryCount >= limits.entryCount -> ScanLimit.ENTRIES
            else -> null
        }

        private fun exceededHardLimit(metrics: ScanMetrics): ScanLimit? = when {
            metrics.pdfCount > HARD_SCAN_THRESHOLDS.pdfCount -> ScanLimit.PDFS
            metrics.directoryCount > HARD_SCAN_THRESHOLDS.directoryCount -> ScanLimit.DIRECTORIES
            metrics.entryCount > HARD_SCAN_THRESHOLDS.entryCount -> ScanLimit.ENTRIES
            else -> null
        }

        private fun requireThresholds(thresholds: ScanThresholds) {
            require(thresholds.pdfCount in 1..(HARD_SCAN_THRESHOLDS.pdfCount + 1))
            require(thresholds.directoryCount in 1..(HARD_SCAN_THRESHOLDS.directoryCount + 1))
            require(thresholds.entryCount in 1..(HARD_SCAN_THRESHOLDS.entryCount + 1))
        }

        private fun joinRelativePath(parent: String, child: String): String =
            if (parent.isEmpty()) child else "$parent/$child"

        private fun documentVisitKey(treeUri: Uri, docId: String): String =
            ComicIdentity.safDocumentKey(treeUri.authority, docId)

        private const val MAX_DEPTH = 15

        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
