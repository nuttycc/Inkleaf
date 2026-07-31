package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

/** Adapts one plugin chapter to the host reader's source-neutral volume contract. */
internal class OnlineChapterVolume(
    private val chapterId: String,
    private val title: String,
    override val sourceRevision: String,
    internal val pages: List<PageDescriptor>,
    private val client: Call.Factory,
) : ComicVolume {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    // Keep source-provided IDs separate from revision/index fallbacks so they cannot collide.
    private val pageIdentities =
        pages.mapIndexed { index, page ->
            page.pageId?.let { pageId -> "id:${pageId.length}:$pageId" }
                ?: "revision-index:${sourceRevision.length}:$sourceRevision:$index"
        }

    init {
        require(chapterId.isNotBlank()) { "chapterId must not be blank" }
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }
        require(pages.isNotEmpty()) { "Online chapter must contain at least one page" }
        require(pages.indices.all { pages[it].index == it }) { "Page indexes must be contiguous" }
        require(pages.all { it.pageId == null || it.pageId.isNotBlank() }) {
            "Page ids must be non-blank"
        }
        val providedPageIds = pages.mapNotNull { it.pageId }
        require(providedPageIds.distinct().size == providedPageIds.size) {
            "Page ids must be unique"
        }
        require(pageIdentities.distinct().size == pageIdentities.size) {
            "Page identities must be unique"
        }
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
        return pageIdentities[requirePage(globalPage)]
    }

    override suspend fun loadPageBytes(globalPage: Int): ByteArray {
        check(!closed.get()) { "Online volume is closed" }
        val page = pages[requirePage(globalPage)]
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
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        calls -= call
                        continuation.resumeWithException(ComicOpenException(e.message ?: "页面下载失败"))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        calls -= call
                        val result = runCatching {
                            response.use {
                                if (!it.isSuccessful) {
                                    throw ComicOpenException("页面请求失败（HTTP ${it.code}）")
                                }
                                it.body?.readPageBytes(PluginRuntimePolicy.MAX_IMAGE_BYTES)
                                    ?: throw ComicOpenException("页面响应为空")
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
}

internal fun ResponseBody.readPageBytes(maxBytes: Long): ByteArray {
    require(maxBytes >= 0L) { "maxBytes must not be negative" }
    if (contentLength() > maxBytes) throw ComicOpenException("页面图像超过大小限制")
    val input = source()
    val output = Buffer()
    var total = 0L
    while (true) {
        val remaining = maxBytes - total
        val readLimit = if (remaining >= 64 * 1024L) 64 * 1024L else remaining + 1L
        val read = input.read(output, readLimit)
        if (read < 0L) break
        total += read
        if (total > maxBytes) throw ComicOpenException("页面图像超过大小限制")
    }
    return output.readByteArray()
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
