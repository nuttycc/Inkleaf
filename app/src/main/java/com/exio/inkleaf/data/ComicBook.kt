package com.exio.inkleaf.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** 打开漫画失败时抛出，message 直接用于界面展示 */
class ComicOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一本已打开的漫画。UI 层只通过 pageCount / loadPageBytes 访问，
 * 完全不需要知道背后是 zip。
 *
 * 调用约定：close() 之后不可再调用 loadPageBytes。
 */
class ComicBook private constructor(
    private val zipFile: ZipFile,
    private val thumbZipFile: ZipFile,
    private val pageEntries: List<ZipEntry>,
) {
    val pageCount: Int get() = pageEntries.size

    /** 读取第 index 页的原始图片字节（jpg/png/webp 压缩数据） */
    suspend fun loadPageBytes(index: Int): ByteArray = withContext(Dispatchers.IO) {
        // ZipFile.getInputStream 内部有同步锁，相邻页并发预加载时自动串行，线程安全
        zipFile.getInputStream(pageEntries[index]).use { it.readBytes() }
    }

    /**
     * 缩略图专用读取通道（胶片导航条用）。
     *
     * 为什么单独开一个 ZipFile 实例：ZipFile 的内部锁是实例级的，
     * 胶片条滑动会排队大量读取，若与正文大图共用一个实例，
     * 两边会在同一把锁上互相阻塞（翻页等缩略图、缩略图等翻页）。
     * 两个实例指向同一文件，锁互不相干，IO 自然分流。
     */
    suspend fun loadThumbnailPageBytes(index: Int): ByteArray = withContext(Dispatchers.IO) {
        thumbZipFile.getInputStream(pageEntries[index]).use { it.readBytes() }
    }

    /** 关闭 zip。副本文件保留在磁盘上供下次秒开（淘汰交给 ReaderCache） */
    fun close() {
        runCatching { zipFile.close() }
        runCatching { thumbZipFile.close() }
    }

    companion object {
        private val IMAGE_EXT = Regex(".*\\.(jpe?g|png|webp|gif)$", RegexOption.IGNORE_CASE)

        /**
         * 打开一本漫画。
         *
         * 为什么需要本地副本？SAF 给的流不支持 seek（跳跃读取），而翻页
         * 需要随机访问任意一页。ZipFile 读取 zip 末尾的目录区后可以 O(1)
         * 定位任意条目，但它只接受真实文件路径。
         *
         * 副本按书持久化（文件名内嵌原文件"大小+修改时间"作为校验键）：
         * 键命中直接复用、整个复制阶段被跳过，重开同一本书近乎瞬时；
         * 原文件被替换则键失配，作废旧副本和缩略图后重新复制。
         */
        suspend fun open(context: Context, uri: Uri, comicId: Long): ComicBook =
            withContext(Dispatchers.IO) {
                // 查不到元数据（个别 provider）时 size=0，视为不可校验，
                // 走"每次重新复制"的保守路径，行为退化为旧版
                val doc = DocumentFile.fromSingleUri(context, uri)
                val sourceSize = doc?.length() ?: 0L
                val sourceMtime = doc?.lastModified() ?: 0L
                val cacheFile =
                    ReaderCache.cachedZipFile(context, comicId, sourceSize, sourceMtime)

                val reused = sourceSize > 0 && cacheFile.exists()
                if (!reused) {
                    // 副本要重建 = 旧副本和缩略图都派生自过期内容，一并作废
                    ReaderCache.wipeBook(context, comicId)
                    copyToCache(context, uri, cacheFile)
                }

                var zipFile: ZipFile? = null
                var thumbZipFile: ZipFile? = null
                try {
                    zipFile = try {
                        ZipFile(cacheFile)
                    } catch (e: ZipException) {
                        if (reused) {
                            // 复用的副本损坏（系统清理截断等，极少见）：
                            // 作废后重新复制一次再试
                            ReaderCache.wipeBook(context, comicId)
                            copyToCache(context, uri, cacheFile)
                            try {
                                ZipFile(cacheFile)
                            } catch (e2: ZipException) {
                                throw ComicOpenException("不是有效的漫画压缩包", e2)
                            }
                        } else {
                            throw ComicOpenException("不是有效的漫画压缩包", e)
                        }
                    }

                    val pages = zipFile.entries().asSequence()
                        .filter { !it.isDirectory && isImageEntry(it.name) }
                        .sortedWith { a, b -> ChapterSort.compareNatural(a.name, b.name) }
                        .toList()

                    if (pages.isEmpty()) {
                        throw ComicOpenException("压缩包里没有找到图片")
                    }

                    // 第一个实例已验证过文件有效，这里基本不会失败；
                    // 代价只是再解析一次 zip 目录区，很小
                    thumbZipFile = ZipFile(cacheFile)

                    // lastModified 即"最近阅读时间"，是 LRU 淘汰的排序依据
                    cacheFile.setLastModified(System.currentTimeMillis())
                    ReaderCache.enforceBudget(context, keep = cacheFile)

                    ComicBook(zipFile, thumbZipFile, pages)
                } catch (e: Throwable) {
                    runCatching { zipFile?.close() }
                    runCatching { thumbZipFile?.close() }
                    // 取消（用户中途退出）不作废副本：已复制完的副本下次还能用；
                    // 真失败（无图片/损坏）才连缓存一起清掉
                    if (e !is CancellationException) {
                        ReaderCache.wipeBook(context, comicId)
                    }
                    throw when (e) {
                        is ComicOpenException, is CancellationException -> e
                        // 持久化权限失效（如系统清理、provider 不支持）时 openInputStream 抛出
                        is SecurityException ->
                            ComicOpenException("没有权限读取该文件，可能已被移动或权限已失效", e)
                        // 原文件被用户删除或移动
                        is FileNotFoundException ->
                            ComicOpenException("找不到原文件，可能已被移动或删除", e)

                        else -> ComicOpenException("打开漫画失败：${e.message}", e)
                    }
                }
            }

        /**
         * 复制到"同目录 .tmp 再原子改名"：进程在复制中途被杀也不会留下
         * 一个貌似完整、实则截断的副本被下次误复用
         */
        private suspend fun copyToCache(context: Context, uri: Uri, dest: File) {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            try {
                val source = context.contentResolver.openInputStream(uri)
                    ?: throw ComicOpenException("无法读取所选文件")
                source.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // 用户在复制大文件期间退出阅读界面时，从这里响应协程取消
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!tmp.renameTo(dest)) {
                    throw ComicOpenException("缓存副本写入失败")
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }

        /** zip 条目是否是有效的漫画页图片（封面回填等场景也复用此判断） */
        fun isImageEntry(entryName: String): Boolean {
            if (entryName.startsWith("__MACOSX/")) return false // macOS 压缩残留目录
            val fileName = entryName.substringAfterLast('/')
            if (fileName.startsWith(".")) return false // 隐藏文件，如 ._page1.jpg
            return IMAGE_EXT.matches(fileName)
        }
    }
}
