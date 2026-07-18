package com.exio.inkleaf.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.ComicGroupEntity
import com.exio.inkleaf.data.db.FolderWithCount
import com.exio.inkleaf.data.db.GroupWithCount
import com.exio.inkleaf.data.db.LibraryFolderEntity
import com.exio.inkleaf.data.db.LibraryFolderType
import com.exio.inkleaf.data.enhancement.EnhancedImageDiskCache
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog
import com.exio.inkleaf.data.enhancement.EnhancementSelectionIds
import com.exio.inkleaf.data.enhancement.cache.EnhancementCacheTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** 一次全库扫描的结果汇总 */
data class ScanResult(
    val added: Int,
    val markedMissing: Int,
    val restored: Int,
    val alreadyInLibrary: Int,
    val failedFolders: List<String>, // 无法访问的目录名
)

/** addFolderAndSync 的结果：目录是新加入的（携带首扫汇总）还是已存在 */
sealed interface AddFolderOutcome {
    data class Added(val scan: ScanResult) : AddFolderOutcome
    data object Duplicate : AddFolderOutcome
}

/** addOrGetComic 的结果：单本是新加入、已存在，还是从失效状态恢复 */
sealed interface AddComicOutcome {
    val comic: ComicEntity

    data class Added(override val comic: ComicEntity) : AddComicOutcome
    data class AlreadyInLibrary(override val comic: ComicEntity) : AddComicOutcome
    data class Restored(override val comic: ComicEntity) : AddComicOutcome
}

/** addSeriesFolderAndSync 的结果：已添加（含章节数）、目录已存在、或没有 PDF */
sealed interface AddSeriesFolderOutcome {
    data class Added(val comic: ComicEntity, val chaptersAdded: Int) : AddSeriesFolderOutcome
    data object Duplicate : AddSeriesFolderOutcome
    data object Empty : AddSeriesFolderOutcome
}

sealed interface GroupWriteOutcome {
    data class Success(val groupId: Long? = null) : GroupWriteOutcome
    data object BlankName : GroupWriteOutcome
    data object Duplicate : GroupWriteOutcome
    data object Missing : GroupWriteOutcome
}

/**
 * 业务数据的统一入口：ViewModel 不直接接触 DAO、文件系统和权限 API。
 * 自身无状态（数据库是单例），随处构造无代价——所以暂时不需要依赖注入框架。
 */
class ComicRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val dao = db.comicDao()
    private val folderDao = db.libraryFolderDao()
    private val groupDao = db.comicGroupDao()
    private val scanner = LibraryScanner(appContext)

    // ===== 漫画条目 =====

    fun observeAll(): Flow<List<ComicEntity>> = dao.observeAll()

    suspend fun getComic(id: Long): ComicEntity? = dao.getById(id)

    suspend fun setEnhancementSelection(comicId: Long, selectionId: String) {
        require(EnhancementSelectionIds.isValid(selectionId)) {
            "未知的图像增强选项：$selectionId"
        }
        dao.updateEnhancementSelection(comicId, selectionId)
    }

    suspend fun resetEnhancementSelections(modelId: String) {
        EnhancementModelCatalog.require(modelId)
        dao.resetEnhancementSelection(modelId, EnhancementSelectionIds.ORIGINAL)
    }

    /** 打开一本书。SAF Uri 的解析收在数据层，UI 不接触存储地址格式 */
    suspend fun openBook(comic: ComicEntity): ComicVolume {
        if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
            return AlbumComicVolume(
                context = appContext,
                pages = db.albumPageDao().getByComicId(comic.id),
                title = comic.title,
            )
        }
        val chapters = db.chapterDao().getByComicId(comic.id)
        return if (comic.sourceType == BookSourceType.PDF_SERIES || chapters.isNotEmpty()) {
            PdfComicVolume(
                context = appContext,
                chapters = chapters,
                pfdResolver = { uriString ->
                    runCatching {
                        appContext.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                    }.getOrNull()
                },
            )
        } else {
            val book = ComicBook.open(appContext, Uri.parse(comic.uri), comic.id)
            ZipComicVolume(book, comic.title)
        }
    }

    // ===== 自定义分组 =====

    fun observeGroups(): Flow<List<GroupWithCount>> = groupDao.observeGroupsWithCount()

    suspend fun groupExists(id: Long): Boolean = groupDao.getById(id) != null

    suspend fun createGroup(name: String): GroupWriteOutcome {
        val normalized = normalizeGroupName(name) ?: return GroupWriteOutcome.BlankName
        if (groupDao.getByName(normalized) != null) return GroupWriteOutcome.Duplicate
        val id = groupDao.insert(
            ComicGroupEntity(
                name = normalized,
                createdAt = System.currentTimeMillis(),
            )
        )
        return if (id == -1L) {
            GroupWriteOutcome.Duplicate
        } else {
            GroupWriteOutcome.Success(id)
        }
    }

    suspend fun renameGroup(id: Long, name: String): GroupWriteOutcome {
        val normalized = normalizeGroupName(name) ?: return GroupWriteOutcome.BlankName
        val group = groupDao.getById(id) ?: return GroupWriteOutcome.Missing
        val sameNameGroup = groupDao.getByName(normalized)
        if (sameNameGroup != null && sameNameGroup.id != group.id) {
            return GroupWriteOutcome.Duplicate
        }
        if (group.name != normalized) {
            groupDao.updateName(id, normalized)
        }
        return GroupWriteOutcome.Success(id)
    }

    suspend fun deleteGroup(id: Long) {
        groupDao.deleteKeepingComics(id)
    }

    suspend fun setComicGroup(comicId: Long, groupId: Long?): GroupWriteOutcome {
        if (groupId != null && groupDao.getById(groupId) == null) {
            return GroupWriteOutcome.Missing
        }
        dao.updateGroup(comicId, groupId)
        return GroupWriteOutcome.Success(groupId)
    }

    /**
     * 手动添加单个漫画；同一文件已在书架时直接返回已有记录，失效记录会恢复。
     *
     * takePersistableUriPermission 把 SAF 的"本次会话可读"升级为
     * "重启后仍可读"。个别文档提供方不支持持久化授权，失败也不阻断添加。
     */
    suspend fun addOrGetComic(uri: Uri): AddComicOutcome {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val uriString = uri.toString()
        val fileKey = withContext(Dispatchers.IO) { ComicIdentity.fileKey(appContext, uri) }
        return syncMutex.withLock {
            dao.getByFileKey(fileKey)?.let { return@withLock existingComicOutcome(it) }
            dao.getByUri(uriString)?.let { return@withLock existingComicOutcome(it) }

            val now = System.currentTimeMillis()
            val id = dao.insert(
                ComicEntity(
                    uri = uriString,
                    fileKey = fileKey,
                    title = guessTitle(uri),
                    addedAt = now,
                    lastReadAt = now,
                )
            )
            if (id != -1L) {
                AddComicOutcome.Added(dao.getById(id)!!)
            } else {
                val existing = dao.getByFileKey(fileKey)
                    ?: dao.getByUri(uriString)
                    ?: error("Comic insert conflicted but no existing row was found")
                existingComicOutcome(existing)
            }
        }
    }

    suspend fun saveProgress(
        id: Long,
        sourceType: BookSourceType,
        chapterIndex: Int,
        page: Int,
    ) {
        if (sourceType == BookSourceType.CREATED_ALBUM) {
            val pageId = db.albumPageDao().getIdByPosition(id, page)
            dao.updateAlbumProgress(id, page, pageId, System.currentTimeMillis())
        } else {
            dao.updateProgress(id, chapterIndex, page, System.currentTimeMillis())
        }
    }

    /** 首次成功打开后回填真实页数和封面；Room Flow 会自动刷新书架 */
    suspend fun backfillMetadata(comic: ComicEntity, volume: ComicVolume) {
        val needCover = comic.coverPath == null || !File(comic.coverPath).exists()
        val cover = if (needCover) {
            if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
                val pages = db.albumPageDao().getByComicId(comic.id)
                val page = pages.firstOrNull { it.id == comic.coverPageId } ?: pages.firstOrNull()
                page?.let {
                    runCatching {
                        Covers.createCoverFile(
                            appContext,
                            comic.id,
                            resolveAlbumPageFile(appContext.filesDir, it.relativePath),
                        )
                    }.getOrNull()
                }
            } else {
                val pageBytes = runCatching { volume.loadPageBytes(0) }.getOrNull()
                pageBytes?.let { Covers.createCoverFile(appContext, comic.id, it) }
            }
        } else {
            null
        }
        if (comic.pageCount != volume.totalPageCount) {
            dao.updatePageCount(comic.id, volume.totalPageCount)
        }
        // PDF 目录需回填各章节页数；单文件漫画（zip/cbz）chapterCount=1，没有章节行，
        // 直接跳过这次 DB 查询，避免每次开书都白跑一趟。
        if (comic.sourceType == BookSourceType.PDF_SERIES && volume.chapterCount > 1) {
            val chapters = db.chapterDao().getByComicId(comic.id)
            chapters.forEach { chapter ->
                val actual = volume.chapterPageCount(chapter.chapterIndex)
                if (actual > 0 && chapter.pageCount != actual) {
                    db.chapterDao().updatePageCount(chapter.id, actual)
                }
            }
        }
        if (cover != null) {
            applyGeneratedCover(comic, cover)
        } else if (needCover && comic.coverPath != null) {
            dao.updateCoverIfUnchanged(comic.id, comic.coverPath, null)
        }
    }

    suspend fun setCoverFromPage(comicId: Long, volume: ComicVolume, page: Int) {
        if (page !in 0 until volume.totalPageCount) {
            throw IllegalArgumentException("页码超出范围")
        }
        val comic = dao.getById(comicId) ?: throw IllegalStateException("书架记录不存在")
        if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
            val pageId = db.albumPageDao().getIdByPosition(comicId, page)
                ?: throw IllegalStateException("图册页面不存在")
            val albumPage = db.albumPageDao().getById(pageId)
                ?: throw IllegalStateException("图册页面不存在")
            val cover = Covers.createCoverFile(
                appContext,
                comicId,
                resolveAlbumPageFile(appContext.filesDir, albumPage.relativePath),
            ) ?: throw IllegalStateException("封面生成失败")
            dao.updateAlbumCover(comicId, cover.absolutePath, pageId)
            comic.coverPath
                ?.let(::File)
                ?.takeIf { it.absolutePath != cover.absolutePath }
                ?.delete()
        } else {
            val bytes = volume.loadPageBytes(page)
            val cover = Covers.createCoverFile(appContext, comicId, bytes)
                ?: throw IllegalStateException("封面生成失败")
            dao.updateCover(comicId, cover.absolutePath)
            comic.coverPath
                ?.let(::File)
                ?.takeIf { it.absolutePath != cover.absolutePath }
                ?.delete()
        }
    }

    /** 从书架移除单本：删记录 + 删派生文件 + 释放权限（不动用户的原文件） */
    suspend fun deleteComic(comic: ComicEntity) {
        if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
            albumFileMutex.withLock {
                dao.deleteById(comic.id)
                deleteComicArtifacts(comic)
            }
            return
        }
        dao.deleteById(comic.id)
        deleteComicArtifacts(comic)
        // PDF 章节目录的书被移除时，一并移除目录记录，避免自动同步把它又加回来
        comic.folderId?.let { folderId ->
            folderDao.getAll().find { it.id == folderId }?.let { folder ->
                if (folder.type == LibraryFolderType.SERIES) {
                    folderDao.deleteById(folder.id)
                }
            }
        }
        if (comic.sourceType != BookSourceType.CREATED_ALBUM) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(comic.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    // ===== 漫画库目录 =====

    fun observeFolders(): Flow<List<FolderWithCount>> = folderDao.observeFoldersWithCount()

    /**
     * 添加库目录。树权限拿不到时扫描必然失败，所以这里不吞 SecurityException，
     * 让调用方提示用户。返回 false 表示目录已存在。
     */
    suspend fun addFolder(treeUri: Uri, type: LibraryFolderType = LibraryFolderType.LIBRARY): Boolean {
        appContext.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        if (folderDao.getByTreeUri(treeUri.toString()) != null) return false

        val name = DocumentFile.fromTreeUri(appContext, treeUri)?.name ?: "未命名目录"
        folderDao.insert(
            LibraryFolderEntity(
                treeUri = treeUri.toString(),
                displayName = name,
                addedAt = System.currentTimeMillis(),
                type = type,
            )
        )
        return true
    }

    /**
     * 移除库目录：应用层手动级联——删该目录扫出的漫画（含封面）、删目录行、
     * 释放树权限。只 release 树 Uri：子文件从未单独 take 过权限
     * （树授权覆盖整棵子树）。手动添加的条目（folderId=null）不受影响。
     */
    suspend fun removeFolder(folder: LibraryFolderEntity) {
        dao.getByFolderId(folder.id).forEach { deleteComicArtifacts(it) }
        dao.deleteByFolderId(folder.id)
        folderDao.deleteById(folder.id)
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * 添加库目录的完整编排：插库 → 首扫 → 补封面。
     * 收在数据层，让多个入口（书架 FAB 菜单、设置页目录管理）共享同一份
     * 业务逻辑——入口可以有多个，编排只能有一份。
     * SecurityException（树权限拿不到）原样上抛，由调用方提示用户
     */
    suspend fun addFolderAndSync(treeUri: Uri): AddFolderOutcome {
        if (!addFolder(treeUri)) return AddFolderOutcome.Duplicate
        val result = syncAllFolders()
        backfillCovers()
        return AddFolderOutcome.Added(result)
    }

    /**
     * 添加 PDF 章节目录：把目录内直接子 PDF 文件作为一本书的章节。
     * 目录已存在时返回 Duplicate；没有 PDF 文件时返回 Empty（调用方应提示用户）。
     * 目录打不开（权限被撤销/被删）时抛 [LibraryScanner.FolderAccessException]，
     * 由调用方提示用户——首次导入就失败时不留库目录记录。
     */
    suspend fun addSeriesFolderAndSync(treeUri: Uri): AddSeriesFolderOutcome {
        if (!addFolder(treeUri, LibraryFolderType.SERIES)) return AddSeriesFolderOutcome.Duplicate

        val pdfs = try {
            scanner.scanPdfs(treeUri)
        } catch (e: LibraryScanner.FolderAccessException) {
            // 目录刚加进库就打不开：回滚库目录记录，避免下次 sync 又抛错
            folderDao.getByTreeUri(treeUri.toString())?.let { folderDao.deleteById(it.id) }
            releaseTreePermission(treeUri)
            throw e
        }
        if (pdfs.isEmpty()) {
            // 没有 PDF 时不留下空目录记录，避免用户之后无法重新导入同一目录
            folderDao.getByTreeUri(treeUri.toString())?.let { folderDao.deleteById(it.id) }
            releaseTreePermission(treeUri)
            return AddSeriesFolderOutcome.Empty
        }

        return syncMutex.withLock {
            val folder = folderDao.getByTreeUri(treeUri.toString())
                ?: return@withLock AddSeriesFolderOutcome.Duplicate
            val comic = ensureSeriesComic(folder, treeUri)
            val added = syncSeriesChapters(comic, pdfs)
            AddSeriesFolderOutcome.Added(comic, added)
        }.also {
            // 首扫成功后尝试补封面（失败不阻断结果提示）
            backfillSeriesCover(it)
        }
    }

    /**
     * 全库同步：对每个目录做"扫描结果 vs 数据库"的集合 diff，三分类处理。
     * 整个过程幂等（重复执行结果相同）——扫描中途被取消/杀进程都无害，
     * 下次重扫即可收敛到正确状态。
     *
     * 进程级锁串行化并发调用（Repository 随处构造，多个 ViewModel 可能
     * 同时发起扫描）：两次扫描从同一快照各算一遍"新书"会重复插入；
     * 排队的那次等到锁后跑的是无变化的空 diff，几乎零成本
     */
    suspend fun syncAllFolders(): ScanResult = syncMutex.withLock {
        var added = 0
        var markedMissing = 0
        var restored = 0
        var alreadyInLibrary = 0
        val failed = mutableListOf<String>()

        for (folder in folderDao.getAll()) {
            when (folder.type) {
                LibraryFolderType.SERIES -> {
                    if (syncSeriesFolder(folder) == null) failed.add(folder.displayName)
                }
                LibraryFolderType.LIBRARY -> {
                    val partial = syncLibraryFolder(folder)
                    added += partial.added
                    markedMissing += partial.markedMissing
                    restored += partial.restored
                    alreadyInLibrary += partial.alreadyInLibrary
                }
            }
        }
        ScanResult(
            added = added,
            markedMissing = markedMissing,
            restored = restored,
            alreadyInLibrary = alreadyInLibrary,
            failedFolders = failed,
        )
    }

    /**
     * 同步单个漫画库目录：每个文件是一本独立漫画。
     * 返回该目录的 diff 计数，由 [syncAllFolders] 汇总。
     */
    private suspend fun syncLibraryFolder(folder: LibraryFolderEntity): ScanResult {
        val scanned = try {
            scanner.scanFolder(Uri.parse(folder.treeUri)).distinctBy { it.fileKey }
        } catch (e: LibraryScanner.FolderAccessException) {
            // 目录打不开：返回空结果，外层把它加入 failed
            return ScanResult(0, 0, 0, 0, emptyList())
        }

        var added = 0
        var markedMissing = 0
        var restored = 0
        var alreadyInLibrary = 0

        val existing = dao.getByFolderId(folder.id)
        val scannedKeys = scanned.map { it.fileKey }.toSet()
        val existingKeys = existing.map { it.fileKey }.toSet()

        // 1) 目录里有、库里没有 → 新书入库
        val candidateFiles = scanned.filter { it.fileKey !in existingKeys }
        val knownComics = getComicsByFileKeys(candidateFiles.map { it.fileKey })
        val knownKeys = knownComics.map { it.fileKey }.toSet()
        val knownMissingIds = knownComics.filter { it.isMissing }.map { it.id }
        if (knownMissingIds.isNotEmpty()) {
            dao.setMissing(knownMissingIds, false)
            restored += knownMissingIds.size
        }
        val newFiles = candidateFiles.filter { it.fileKey !in knownKeys }
        alreadyInLibrary += candidateFiles.size - newFiles.size
        if (newFiles.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val insertedIds = dao.insertAll(
                newFiles.map {
                    ComicEntity(
                        uri = it.uri,
                        fileKey = it.fileKey,
                        title = titleFromFileName(it.displayName),
                        folderId = folder.id,
                        addedAt = now,
                    )
                }
            )
            added += insertedIds.count { it != -1L }
        }

        // 2) 库里有、目录里没有 → 标记失效（保留进度，文件回来可恢复）
        val toMiss = existing
            .filter { !it.isMissing && it.fileKey !in scannedKeys }
            .map { it.id }
        if (toMiss.isNotEmpty()) {
            dao.setMissing(toMiss, true)
            markedMissing += toMiss.size
        }

        // 3) 之前失效、现在又扫到了 → 恢复
        val toRestore = existing
            .filter { it.isMissing && it.fileKey in scannedKeys }
            .map { it.id }
        if (toRestore.isNotEmpty()) {
            dao.setMissing(toRestore, false)
            restored += toRestore.size
        }

        return ScanResult(added, markedMissing, restored, alreadyInLibrary, emptyList())
    }

    /**
     * 同步单个 PDF 章节目录：目录内每个 PDF 是一本书的章节。
     * 返回新增章节数；返回 null 表示目录无法访问。
     */
    private suspend fun syncSeriesFolder(folder: LibraryFolderEntity): Int? {
        val pdfs = try {
            scanner.scanPdfs(Uri.parse(folder.treeUri))
        } catch (e: LibraryScanner.FolderAccessException) {
            return null
        }

        val comic = ensureSeriesComic(folder, Uri.parse(folder.treeUri))
        if (pdfs.isEmpty()) {
            // 目录里的 PDF 全被删光：把漫画标为失效，但不删记录和进度
            if (!comic.isMissing) dao.setMissing(listOf(comic.id), true)
            return 0
        }
        if (comic.isMissing) dao.setMissing(listOf(comic.id), false)
        return syncSeriesChapters(comic, pdfs)
    }

    /**
     * 保证指定 SERIES 目录在 comics 表里有一行记录。没有则插入，
     * 有则返回已有记录（含失效记录）。
     */
    private suspend fun ensureSeriesComic(folder: LibraryFolderEntity, treeUri: Uri): ComicEntity {
        val existing = dao.getByFolderId(folder.id).firstOrNull()
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val folderKey = ComicIdentity.folderKey(treeUri)
        val id = dao.insert(
            ComicEntity(
                uri = treeUri.toString(),
                fileKey = folderKey,
                title = folder.displayName,
                addedAt = now,
                lastReadAt = now,
                folderId = folder.id,
                sourceType = BookSourceType.PDF_SERIES,
            )
        )
        return dao.getById(id) ?: error("系列漫画插入失败")
    }

    /**
     * 按扫描到的 PDF 列表同步章节表：新增、恢复、标记失效、清理已移除章节。
     * diff 计算在 [ChapterSync.computeDiff]（纯函数，单测覆盖），落库在
     * [ChapterDao.applyDiff]（一个事务，原子提交）。返回实际新增的章节数。
     */
    private suspend fun syncSeriesChapters(
        comic: ComicEntity,
        scannedPdfs: List<LibraryScanner.ScannedFile>,
    ): Int {
        val chapterDao = db.chapterDao()
        val existing = chapterDao.getByComicId(comic.id)
        val diff = ChapterSync.computeDiff(
            existing = existing,
            scanned = scannedPdfs,
            comicId = comic.id,
            titleOf = ::titleFromFileName,
        )
        chapterDao.applyDiff(diff)

        // 章节重排后，用户上次读到的章节可能挪到了新位置。按 fileKey 跟踪
        // 把 lastReadChapterIndex 重新指向同一章节的新索引——否则中间插章
        // 会让进度错位到新插入的章节。原章节被删/失效时不重写，打开时
        // coerceIn 兜底，进度不丢。
        ChapterSync.remapChapterIndex(existing, diff, comic.lastReadChapterIndex)?.let { newIndex ->
            dao.updateLastReadChapterIndex(comic.id, newIndex)
        }

        return diff.addedCount
    }

    /**
     * 给 PDF 章节目录补封面。首次导入时 comics 表刚插入，封面为空；
     * 用 [PdfComicVolume] 读第一章第一页生成封面。
     *
     * 只打开第一章而不是全书：totalPageCount 会触发 computeStartPages，
     * 它会逐章打开 PDF 拿页数——多章目录在导入时会被卡住。封面只需知道
     * 第一章有没有页，用 chapterPageCount(0) 即可。所有 PDF 打开都走
     * Dispatchers.IO，避免在主线程做 native 解析。
     */
    private suspend fun backfillSeriesCover(outcome: AddSeriesFolderOutcome) {
        val comic = (outcome as? AddSeriesFolderOutcome.Added)?.comic ?: return
        val updatedComic = dao.getById(comic.id) ?: return
        if (updatedComic.coverPath != null && File(updatedComic.coverPath).exists()) return

        withContext(Dispatchers.IO) {
            val volume = runCatching { openBook(updatedComic) }.getOrNull() ?: return@withContext
            try {
                if (volume.chapterPageCount(0) > 0) {
                    val bytes = runCatching { volume.loadPageBytes(0) }.getOrNull()
                    bytes?.let {
                        val cover = Covers.createCoverFile(appContext, updatedComic.id, it)
                        cover?.let { file -> applyGeneratedCover(updatedComic, file) }
                    }
                }
            } finally {
                volume.close()
            }
        }
    }

    private fun releaseTreePermission(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * 给没有封面的书补封面。用 ZipInputStream 顺序流读到第一个图片条目就停，
     * 不需要像阅读那样把整个文件复制到缓存（封面不需要随机访问）。
     * 每补一张就 UPDATE 一次 → 书架上封面逐张"长出来"。
     */
    suspend fun backfillCovers() = withContext(Dispatchers.IO) {
        for (comic in dao.getComicsWithoutCover()) {
            coroutineContext.ensureActive() // 用户退出时及时停下，下次继续（幂等）
            if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
                val pages = db.albumPageDao().getByComicId(comic.id)
                val coverPage =
                    pages.firstOrNull { it.id == comic.coverPageId } ?: pages.firstOrNull()
                val file = coverPage
                    ?.let {
                        runCatching {
                            resolveAlbumPageFile(
                                appContext.filesDir,
                                it.relativePath
                            )
                        }.getOrNull()
                    }
                    ?.takeIf(File::isFile)
                    ?: continue
                val cover = Covers.createCoverFile(appContext, comic.id, file) ?: continue
                applyGeneratedCover(comic, cover)
                continue
            }
            // PDF 章节目录的封面在首次导入或首次打开时通过 PdfComicVolume 生成
            if (db.chapterDao().countByComicId(comic.id) > 0) continue
            val bytes = runCatching { readFirstImageBytes(Uri.parse(comic.uri)) }
                .getOrNull() ?: continue
            val cover = Covers.createCoverFile(appContext, comic.id, bytes) ?: continue
            applyGeneratedCover(comic, cover)
        }
    }

    /**
     * 顺序读 zip 流，返回第一个图片条目的字节。
     * 注意 zip 条目顺序未必等于页码顺序，取到的可能不是第 1 页——
     * 作为封面缩略图够用；首次打开时 backfillMetadata 不会覆盖已有封面。
     */
    private fun readFirstImageBytes(uri: Uri): ByteArray? {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && ComicBook.isImageEntry(entry.name)) {
                        return zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    /** 用 DocumentFile 查询文件的显示名（SAF Uri 本身不含可读文件名） */
    private suspend fun guessTitle(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = DocumentFile.fromSingleUri(appContext, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "未命名漫画"
        titleFromFileName(name)
    }

    /** 文件名 → 书架标题的唯一规则（手动添加与目录扫描两条入库路径共用） */
    private fun titleFromFileName(name: String): String = name.substringBeforeLast('.')

    /**
     * 自动生成的封面落库。updateCoverIfUnchanged 只在行内 coverPath 仍是
     * 快照值时生效——期间用户手动换过封面则放弃并删掉刚生成的文件
     */
    private suspend fun applyGeneratedCover(comic: ComicEntity, cover: File) {
        val updated = dao.updateCoverIfUnchanged(
            comic.id,
            comic.coverPath,
            cover.absolutePath,
        )
        if (updated == 0) {
            cover.delete()
        }
    }

    /** 删除一本书的全部派生磁盘产物：封面文件 + 阅读缓存（zip 副本、缩略图） */
    private suspend fun deleteComicArtifacts(comic: ComicEntity) {
        comic.coverPath?.let { File(it).delete() }
        if (comic.sourceType == BookSourceType.CREATED_ALBUM) {
            File(appContext.filesDir, "albums/${comic.id}").deleteRecursively()
        }
        ReaderCache.wipeBook(appContext, comic.id)
        EnhancementCacheTaskRepository.getInstance(appContext).deleteForComic(comic.id)
        EnhancedImageDiskCache.getInstance(appContext).deleteComic(comic.id)
    }

    private suspend fun existingComicOutcome(comic: ComicEntity): AddComicOutcome {
        if (!comic.isMissing) return AddComicOutcome.AlreadyInLibrary(comic)
        dao.setMissing(listOf(comic.id), false)
        return AddComicOutcome.Restored(comic.copy(isMissing = false))
    }

    private suspend fun getComicsByFileKeys(fileKeys: List<String>): List<ComicEntity> {
        val distinctKeys = fileKeys.distinct()
        if (distinctKeys.isEmpty()) return emptyList()
        return distinctKeys.chunked(SQLITE_MAX_VARIABLES).flatMap { dao.getByFileKeys(it) }
    }

    private fun normalizeGroupName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() }

    companion object {
        /** Repository 实例随处构造（自身无状态），扫描锁必须放进程级单例处 */
        private val syncMutex = Mutex()
        private const val SQLITE_MAX_VARIABLES = 500
    }
}
