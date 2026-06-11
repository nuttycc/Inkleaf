package com.exio.comicreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.exio.comicreader.data.db.AppDatabase
import com.exio.comicreader.data.db.ComicEntity
import com.exio.comicreader.data.db.FolderWithCount
import com.exio.comicreader.data.db.LibraryFolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** 一次全库扫描的结果汇总 */
data class ScanResult(
    val added: Int,
    val markedMissing: Int,
    val restored: Int,
    val failedFolders: List<String>, // 无法访问的目录名
)

/**
 * 业务数据的统一入口：ViewModel 不直接接触 DAO、文件系统和权限 API。
 * 自身无状态（数据库是单例），随处构造无代价——所以暂时不需要依赖注入框架。
 */
class ComicRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).comicDao()
    private val folderDao = AppDatabase.getInstance(appContext).libraryFolderDao()
    private val scanner = LibraryScanner(appContext)

    // ===== 漫画条目 =====

    fun observeAll(): Flow<List<ComicEntity>> = dao.observeAll()

    suspend fun getComic(id: Long): ComicEntity? = dao.getById(id)

    /**
     * 手动添加单个漫画；同一文件已在书架时直接返回已有记录（自然恢复其进度）。
     *
     * takePersistableUriPermission 把 SAF 的"本次会话可读"升级为
     * "重启后仍可读"。个别文档提供方不支持持久化授权，失败也不阻断添加。
     */
    suspend fun addOrGetComic(uri: Uri): ComicEntity {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        // 已知简化：同一文件经单文件选择和目录扫描会得到不同的 uri 字符串，
        // 可能产生重复条目。干净的修法是按 DocumentsContract.getDocumentId
        // 比对（两种 uri 解出的 documentId 相同），留作以后改进
        dao.getByUri(uri.toString())?.let { return it }

        val now = System.currentTimeMillis()
        val id = dao.insert(
            ComicEntity(
                uri = uri.toString(),
                title = guessTitle(uri),
                addedAt = now,
                lastReadAt = now
            )
        )
        return dao.getById(id)!!
    }

    suspend fun saveProgress(id: Long, page: Int) {
        dao.updateProgress(id, page, System.currentTimeMillis())
    }

    /** 首次成功打开后回填真实页数和封面；Room Flow 会自动刷新书架 */
    suspend fun backfillMetadata(comic: ComicEntity, book: ComicBook) {
        val needCover = comic.coverPath == null || !File(comic.coverPath).exists()
        val coverPath = if (needCover) {
            val firstPage = runCatching { book.loadPageBytes(0) }.getOrNull()
            firstPage?.let { Covers.createCoverFile(appContext, comic.id, it)?.absolutePath }
        } else {
            comic.coverPath
        }
        if (comic.pageCount != book.pageCount || coverPath != comic.coverPath) {
            dao.updateMetadata(comic.id, book.pageCount, coverPath)
        }
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
     * 全库同步：对每个目录做"扫描结果 vs 数据库"的集合 diff，三分类处理。
     * 整个过程幂等（重复执行结果相同）——扫描中途被取消/杀进程都无害，
     * 下次重扫即可收敛到正确状态。
     */
    suspend fun syncAllFolders(): ScanResult {
        var added = 0
        var markedMissing = 0
        var restored = 0
        val failed = mutableListOf<String>()

        for (folder in folderDao.getAll()) {
            val scanned = try {
                scanner.scanFolder(Uri.parse(folder.treeUri))
            } catch (e: LibraryScanner.FolderAccessException) {
                // 目录打不开：跳过 diff。绝不能当成"空目录"，
                // 否则会把它的所有书误标为失效
                failed.add(folder.displayName)
                continue
            }

            val existing = dao.getByFolderId(folder.id)
            val scannedUris = scanned.map { it.uri }.toSet()
            val existingUris = existing.map { it.uri }.toSet()

            // 1) 目录里有、库里没有 → 新书入库
            val newFiles = scanned.filter { it.uri !in existingUris }
            if (newFiles.isNotEmpty()) {
                val now = System.currentTimeMillis()
                dao.insertAll(
                    newFiles.map {
                        ComicEntity(
                            uri = it.uri,
                            title = it.displayName.substringBeforeLast('.'),
                            folderId = folder.id,
                            addedAt = now,
                        )
                    }
                )
                added += newFiles.size
            }

            // 2) 库里有、目录里没有 → 标记失效（保留进度，文件回来可恢复）
            val toMiss = existing.filter { !it.isMissing && it.uri !in scannedUris }.map { it.id }
            if (toMiss.isNotEmpty()) {
                dao.setMissing(toMiss, true)
                markedMissing += toMiss.size
            }

            // 3) 之前失效、现在又扫到了 → 恢复
            val toRestore = existing.filter { it.isMissing && it.uri in scannedUris }.map { it.id }
            if (toRestore.isNotEmpty()) {
                dao.setMissing(toRestore, false)
                restored += toRestore.size
            }
        }
        return ScanResult(added, markedMissing, restored, failed)
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
            dao.updateCover(comic.id, cover.absolutePath)
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
}
