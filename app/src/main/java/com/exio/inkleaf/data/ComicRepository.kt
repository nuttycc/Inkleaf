package com.exio.inkleaf.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.ComicGroupEntity
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.FolderWithCount
import com.exio.inkleaf.data.db.GroupWithCount
import com.exio.inkleaf.data.db.LibraryFolderEntity
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
    data class Added(val comic: ComicEntity) : AddComicOutcome
    data class AlreadyInLibrary(val comic: ComicEntity) : AddComicOutcome
    data class Restored(val comic: ComicEntity) : AddComicOutcome
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
    private val dao = AppDatabase.getInstance(appContext).comicDao()
    private val folderDao = AppDatabase.getInstance(appContext).libraryFolderDao()
    private val groupDao = AppDatabase.getInstance(appContext).comicGroupDao()
    private val scanner = LibraryScanner(appContext)

    // ===== 漫画条目 =====

    fun observeAll(): Flow<List<ComicEntity>> = dao.observeAll()

    suspend fun getComic(id: Long): ComicEntity? = dao.getById(id)

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

    suspend fun saveProgress(id: Long, page: Int) {
        dao.updateProgress(id, page, System.currentTimeMillis())
    }

    /** 首次成功打开后回填真实页数和封面；Room Flow 会自动刷新书架 */
    suspend fun backfillMetadata(comic: ComicEntity, book: ComicBook) {
        val needCover = comic.coverPath == null || !File(comic.coverPath).exists()
        val cover = if (needCover) {
            val firstPage = runCatching { book.loadPageBytes(0) }.getOrNull()
            firstPage?.let { Covers.createCoverFile(appContext, comic.id, it) }
        } else {
            null
        }
        if (comic.pageCount != book.pageCount) {
            dao.updatePageCount(comic.id, book.pageCount)
        }
        if (cover != null) {
            val updated = dao.updateCoverIfUnchanged(
                comic.id,
                comic.coverPath,
                cover.absolutePath,
            )
            if (updated == 0) {
                cover.delete()
            }
        } else if (needCover && comic.coverPath != null) {
            dao.updateCoverIfUnchanged(comic.id, comic.coverPath, null)
        }
    }

    suspend fun setCoverFromPage(comicId: Long, book: ComicBook, page: Int) {
        if (page !in 0 until book.pageCount) {
            throw IllegalArgumentException("页码超出范围")
        }
        val comic = dao.getById(comicId) ?: throw IllegalStateException("书架记录不存在")
        val bytes = book.loadPageBytes(page)
        val cover = Covers.createCoverFile(appContext, comicId, bytes)
            ?: throw IllegalStateException("封面生成失败")
        dao.updateCover(comicId, cover.absolutePath)
        comic.coverPath
            ?.let(::File)
            ?.takeIf { it.absolutePath != cover.absolutePath }
            ?.delete()
    }

    /** 从书架移除单本：删记录 + 删封面 + 删磁盘缓存 + 释放权限（不动用户的原文件） */
    suspend fun deleteComic(comic: ComicEntity) {
        dao.deleteById(comic.id)
        comic.coverPath?.let { File(it).delete() }
        ReaderCache.wipeBook(appContext, comic.id)
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(comic.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    // ===== 漫画库目录 =====

    fun observeFolders(): Flow<List<FolderWithCount>> = folderDao.observeFoldersWithCount()

    /**
     * 添加库目录。树权限拿不到时扫描必然失败，所以这里不吞 SecurityException，
     * 让调用方提示用户。返回 false 表示目录已存在。
     */
    suspend fun addFolder(treeUri: Uri): Boolean {
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
        dao.getByFolderId(folder.id).forEach { comic ->
            comic.coverPath?.let { File(it).delete() }
            ReaderCache.wipeBook(appContext, comic.id)
        }
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
            val scanned = try {
                scanner.scanFolder(Uri.parse(folder.treeUri)).distinctBy { it.fileKey }
            } catch (e: LibraryScanner.FolderAccessException) {
                // 目录打不开：跳过 diff。绝不能当成"空目录"，
                // 否则会把它的所有书误标为失效
                failed.add(folder.displayName)
                continue
            }

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
                            title = it.displayName.substringBeforeLast('.'),
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
     * 给没有封面的书补封面。用 ZipInputStream 顺序流读到第一个图片条目就停，
     * 不需要像阅读那样把整个文件复制到缓存（封面不需要随机访问）。
     * 每补一张就 UPDATE 一次 → 书架上封面逐张"长出来"。
     */
    suspend fun backfillCovers() = withContext(Dispatchers.IO) {
        for (comic in dao.getComicsWithoutCover()) {
            coroutineContext.ensureActive() // 用户退出时及时停下，下次继续（幂等）
            val bytes = runCatching { readFirstImageBytes(Uri.parse(comic.uri)) }
                .getOrNull() ?: continue
            val cover = Covers.createCoverFile(appContext, comic.id, bytes) ?: continue
            val updated = dao.updateCoverIfUnchanged(
                comic.id,
                comic.coverPath,
                cover.absolutePath,
            )
            if (updated == 0) {
                cover.delete()
            }
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
        name.substringBeforeLast('.')
    }

    private suspend fun restoreIfMissing(comic: ComicEntity): ComicEntity {
        if (!comic.isMissing) return comic
        dao.setMissing(listOf(comic.id), false)
        return comic.copy(isMissing = false)
    }

    private suspend fun existingComicOutcome(comic: ComicEntity): AddComicOutcome {
        return if (comic.isMissing) {
            AddComicOutcome.Restored(restoreIfMissing(comic))
        } else {
            AddComicOutcome.AlreadyInLibrary(comic)
        }
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
