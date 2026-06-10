package com.exio.comicreader.data

import android.content.Context
import android.net.Uri
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
    private val cacheFile: File,
    private val pageEntries: List<ZipEntry>,
) {
    val pageCount: Int get() = pageEntries.size

    /** 读取第 index 页的原始图片字节（jpg/png/webp 压缩数据） */
    suspend fun loadPageBytes(index: Int): ByteArray = withContext(Dispatchers.IO) {
        // ZipFile.getInputStream 内部有同步锁，相邻页并发预加载时自动串行，线程安全
        zipFile.getInputStream(pageEntries[index]).use { it.readBytes() }
    }

    /** 关闭 zip 并删除缓存副本 */
    fun close() {
        runCatching { zipFile.close() }
        cacheFile.delete()
    }

    companion object {
        /** 固定文件名：打开新书自然覆盖旧副本，即使上次没清理也不会累积 */
        const val CACHE_FILE_NAME = "current_comic.zip"

        private val IMAGE_EXT = Regex(".*\\.(jpe?g|png|webp|gif)$", RegexOption.IGNORE_CASE)

        /**
         * 打开一本漫画：把 content:// Uri 复制到 cacheDir，再用 ZipFile 随机访问。
         *
         * 为什么要复制？SAF 给的流不支持 seek（跳跃读取），而翻页需要随机访问
         * 任意一页。ZipFile 读取 zip 末尾的目录区后可以 O(1) 定位任意条目，
         * 但它只接受真实文件路径，所以先落地一份副本。
         */
        suspend fun open(context: Context, uri: Uri): ComicBook = withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            var zipFile: ZipFile? = null
            try {
                copyToCache(context, uri, cacheFile)

                zipFile = try {
                    ZipFile(cacheFile)
                } catch (e: ZipException) {
                    throw ComicOpenException("不是有效的漫画压缩包", e)
                }

                val pages = zipFile.entries().asSequence()
                    .filter { !it.isDirectory && isComicPage(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .toList()

                if (pages.isEmpty()) {
                    throw ComicOpenException("压缩包里没有找到图片")
                }

                ComicBook(zipFile, cacheFile, pages)
            } catch (e: Throwable) {
                // 失败或中途取消：关闭已打开的 zip、删除半成品副本，再向上抛
                runCatching { zipFile?.close() }
                cacheFile.delete()
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

        private suspend fun copyToCache(context: Context, uri: Uri, dest: File) {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw ComicOpenException("无法读取所选文件")
            source.use { input ->
                dest.outputStream().use { output ->
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
        }

        private fun isComicPage(entryName: String): Boolean = isImageEntry(entryName)

        /** zip 条目是否是有效的漫画页图片（封面回填等场景也复用此判断） */
        fun isImageEntry(entryName: String): Boolean {
            if (entryName.startsWith("__MACOSX/")) return false // macOS 压缩残留目录
            val fileName = entryName.substringAfterLast('/')
            if (fileName.startsWith(".")) return false // 隐藏文件，如 ._page1.jpg
            return IMAGE_EXT.matches(fileName)
        }

        /**
         * 自然排序：把连续数字当作数值比较，"page2.jpg" < "page10.jpg"。
         * 纯字典序会把 10 排在 2 前面，导致阅读顺序错乱。
         */
        private fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var endA = i
                    while (endA < a.length && a[endA].isDigit()) endA++
                    var endB = j
                    while (endB < b.length && b[endB].isDigit()) endB++
                    val numA = a.substring(i, endA).trimStart('0')
                    val numB = b.substring(j, endB).trimStart('0')
                    val cmp = if (numA.length != numB.length) {
                        numA.length - numB.length // 位数多的数值大
                    } else {
                        numA.compareTo(numB)
                    }
                    if (cmp != 0) return cmp
                    i = endA
                    j = endB
                } else {
                    val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cmp != 0) return cmp
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
