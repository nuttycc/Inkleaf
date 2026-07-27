package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Adapts one plugin chapter to the host reader's source-neutral volume contract. */
internal class OnlineChapterVolume(
    private val chapterId: String,
    private val title: String,
    override val sourceRevision: String,
    internal val pages: List<PageDescriptor>,
    private val client: OkHttpClient,
) : ComicVolume {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    init {
        require(chapterId.isNotBlank()) { "chapterId must not be blank" }
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }
        require(pages.isNotEmpty()) { "Online chapter must contain at least one page" }
        require(pages.indices.all { pages[it].index == it }) { "Page indexes must be contiguous" }
    }

    override val totalPageCount: Int = pages.size
    override val chapterCount: Int = 1

    override fun chapterTitle(chapterIndex: Int): String {
        require(chapterIndex == 0) { "Online volume contains one chapter" }
        return title
    }

    override fun chapterStartPage(chapterIndex: Int): Int {
        require(chapterIndex == 0) { "Online volume contains one chapter" }
        return 0
    }

    override fun chapterPageCount(chapterIndex: Int): Int {
        require(chapterIndex == 0) { "Online volume contains one chapter" }
        return totalPageCount
    }

    override fun globalToChapterPage(globalPage: Int): ChapterProgress {
        requirePage(globalPage)
        return ChapterProgress(chapterIndex = 0, pageIndex = globalPage)
    }

    override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int {
        require(chapterIndex == 0) { "Online volume contains one chapter" }
        requirePage(pageIndex)
        return pageIndex
    }

    override fun pageIdentity(globalPage: Int): String {
        val page = pages[requirePage(globalPage)]
        return page.pageId ?: "revision:$sourceRevision:index:$globalPage"
    }

    override suspend fun loadPageBytes(globalPage: Int): ByteArray {
        check(!closed.get()) { "Online volume is closed" }
        val page = pages[requirePage(globalPage)]
        validatePageUrl(page.url)
        val request =
            Request.Builder()
                .url(page.url)
                .apply {
                    page.headers.forEach { (name, value) -> header(name, value) }
                    page.referer?.let { header("Referer", it) }
                }
                .build()
        return execute(request)
    }

    override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray =
        loadPageBytes(globalPage)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.forEach { it.cancel() }
        calls.clear()
    }

    private fun requirePage(globalPage: Int): Int {
        require(globalPage in pages.indices) { "Page index is out of bounds" }
        return globalPage
    }

    private fun validatePageUrl(url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw ComicOpenException("无效的页面 URL")
        val host = httpUrl.host
        try {
            val addresses = InetAddress.getAllByName(host)
            for (addr in addresses) {
                if (
                    addr.isLoopbackAddress ||
                        addr.isLinkLocalAddress ||
                        addr.isSiteLocalAddress ||
                        addr.isAnyLocalAddress
                ) {
                    throw ComicOpenException("页面 URL 指向非公共地址")
                }
            }
        } catch (e: ComicOpenException) {
            throw e
        } catch (e: Exception) {
            throw ComicOpenException("无法解析页面 URL 主机名")
        }
    }

    private suspend fun execute(request: Request): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            if (closed.get()) {
                continuation.cancel(IllegalStateException("Online volume is closed"))
                return@suspendCancellableCoroutine
            }
            calls += call
            if (closed.get()) {
                calls -= call
                call.cancel()
                continuation.cancel(IllegalStateException("Online volume is closed"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                calls -= call
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        calls -= call
                        continuation.resumeWithException(
                            ComicOpenException(error.message ?: "页面下载失败")
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        calls -= call
                        val result = runCatching {
                            response.use {
                                if (!it.isSuccessful) {
                                    throw ComicOpenException("页面请求失败（HTTP ${it.code}）")
                                }
                                val contentLength = it.body?.contentLength() ?: -1L
                                if (contentLength > MAX_PAGE_IMAGE_BYTES) {
                                    throw ComicOpenException("页面图片超过大小限制")
                                }
                                val bytes = it.body?.bytes() ?: throw ComicOpenException("页面响应为空")
                                if (bytes.size > MAX_PAGE_IMAGE_BYTES) {
                                    throw ComicOpenException("页面图片超过大小限制")
                                }
                                bytes
                            }
                        }
                        result.fold(
                            onSuccess = { bytes ->
                                continuation.resume(bytes) { _, _, _ -> }
                            },
                            onFailure = { error ->
                                continuation.resumeWithException(error)
                            },
                        )
                    }
                }
            )
        }

    private companion object {
        const val MAX_PAGE_IMAGE_BYTES = 50L * 1024L * 1024L // 50 MB
    }
}

/** Resolves a revision without ever treating a remote image URL as durable identity. */
internal fun resolveOnlineChapterRevision(
    chapterId: String,
    requestedRevision: String?,
    response: PluginPagesResponse,
): String {
    response.revision?.takeIf(String::isNotBlank)?.let {
        return it
    }
    requestedRevision?.takeIf(String::isNotBlank)?.let {
        return it
    }
    val pageIds = response.pages.map { it.pageId?.takeIf(String::isNotBlank) }
    if (pageIds.any { it == null }) {
        throw ComicOpenException("插件页面缺少稳定 ID，且未提供章节版本")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(chapterId.toByteArray(Charsets.UTF_8))
    pageIds.forEach { pageId ->
        digest.update(0.toByte())
        digest.update(requireNotNull(pageId).toByteArray(Charsets.UTF_8))
    }
    return "page-ids:${digest.digest().toLowerHex()}"
}

private fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
