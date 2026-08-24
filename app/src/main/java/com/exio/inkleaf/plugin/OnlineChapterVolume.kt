package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.OnlinePageCache
import com.exio.inkleaf.data.OnlinePageCacheIdentity
import com.exio.inkleaf.data.OnlinePageCacheKey
import com.exio.inkleaf.data.OnlinePageLoadPriority
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

/** Adapts one plugin chapter to the host reader's source-neutral volume contract. */
internal data class OnlineChapterRefresh(
    val pages: List<PageDescriptor>,
    val cacheIdentity: OnlinePageCacheIdentity,
)

internal class OnlineChapterVolume(
    private val chapterId: String,
    private val title: String,
    override val sourceRevision: String,
    pages: List<PageDescriptor>,
    private val client: Call.Factory,
    private val cache: OnlinePageCache,
    initialCacheIdentity: OnlinePageCacheIdentity,
    private val refreshChapter: (suspend () -> OnlineChapterRefresh?)? = null,
    private val networkRetryDelaysMillis: List<Long> = DEFAULT_NETWORK_RETRY_DELAYS_MILLIS,
) : ComicVolume {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val refreshMutex = Mutex()
    private val decodeRetriedPages = ConcurrentHashMap.newKeySet<Int>()
    private val protectedIdentities = ConcurrentHashMap.newKeySet<OnlinePageCacheIdentity>()
    private val protectionLock = Any()
    @Volatile private var currentSource = ChapterSource(pages, initialCacheIdentity)
    // Keep source-provided IDs separate from revision/index fallbacks so they cannot collide.
    private val pageIdentities = onlinePageIdentities(sourceRevision, pages)

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
        protect(initialCacheIdentity)
    }

    internal val pages: List<PageDescriptor>
        get() = currentSource.pages

    internal val cacheKeyPrefix: String
        get() = currentSource.cacheIdentity.readerCacheKeyPrefix

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
        return loadPage(
            globalPage,
            OnlinePageLoadPriority.FOREGROUND,
            allowDescriptorRefresh = true,
        )
    }

    override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray =
        loadPage(globalPage, OnlinePageLoadPriority.SPECULATIVE, allowDescriptorRefresh = true)

    internal suspend fun prefetchPage(globalPage: Int) {
        loadPage(globalPage, OnlinePageLoadPriority.SPECULATIVE, allowDescriptorRefresh = false)
    }

    internal fun replaceDescriptors(refresh: OnlineChapterRefresh): Boolean {
        if (!sameStablePages(refresh.pages)) return false
        currentSource = ChapterSource(refresh.pages, refresh.cacheIdentity)
        protect(refresh.cacheIdentity)
        // The replacement brings fresh bytes for the same stable pages, so each page deserves
        // a new decode retry against the new cache identity.
        decodeRetriedPages.clear()
        return true
    }

    override fun invalidatePage(globalPage: Int): Boolean {
        val page = requirePage(globalPage)
        if (!decodeRetriedPages.add(page)) return false
        cache.invalidate(targetFor(page).key)
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.forEach { it.cancel() }
        calls.clear()
        // protect() and this release section share protectionLock so a concurrent protect()
        // cannot add a cache protection after close() has released and cleared the set.
        synchronized(protectionLock) {
            protectedIdentities.forEach(cache::releaseChapter)
            protectedIdentities.clear()
        }
    }

    private fun requirePage(globalPage: Int): Int {
        require(globalPage in pageIdentities.indices) { "Page index is out of bounds" }
        return globalPage
    }

    private suspend fun loadPage(
        globalPage: Int,
        priority: OnlinePageLoadPriority,
        allowDescriptorRefresh: Boolean,
    ): ByteArray {
        check(!closed.get()) { "Online volume is closed" }
        val page = requirePage(globalPage)
        val target = targetFor(page)
        return try {
            cache.getOrLoad(target.key, priority) {
                download(target.descriptor, priority)
            }
        } catch (error: PageResponseException) {
            if (
                allowDescriptorRefresh &&
                    error.code in DESCRIPTOR_REFRESH_CODES &&
                    refreshTarget(target, page)
            ) {
                loadPage(page, priority, allowDescriptorRefresh = false)
            } else {
                throw error.toComicOpenException()
            }
        }
    }

    private suspend fun refreshTarget(failed: PageTarget, page: Int): Boolean =
        refreshMutex.withLock {
            if (targetFor(page) != failed) return@withLock true
            val refreshed = refreshChapter?.invoke() ?: return@withLock false
            replaceDescriptors(refreshed)
        }

    private fun targetFor(page: Int): PageTarget {
        val source = currentSource
        return PageTarget(
            descriptor = source.pages[page],
            key = OnlinePageCacheKey.create(source.cacheIdentity, pageIdentities[page]),
        )
    }

    private fun sameStablePages(candidate: List<PageDescriptor>): Boolean {
        if (candidate.size != pageIdentities.size) return false
        return onlinePageIdentities(sourceRevision, candidate) == pageIdentities
    }

    private fun protect(identity: OnlinePageCacheIdentity) {
        synchronized(protectionLock) {
            if (closed.get()) return
            if (protectedIdentities.add(identity)) cache.protectChapter(identity)
        }
    }

    private suspend fun download(
        page: PageDescriptor,
        priority: OnlinePageLoadPriority,
    ): ByteArray {
        // The request is immutable and body-less, so one instance is safe to reuse across retries.
        val request =
            Request.Builder()
                .url(page.url)
                .apply {
                    page.headers.forEach { (name, value) -> header(name, value) }
                    page.referer?.let { header("Referer", it) }
                }
                .build()
        var retry = false
        var networkAttempt = 0
        while (true) {
            try {
                return execute(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (
                    priority == OnlinePageLoadPriority.FOREGROUND &&
                        networkAttempt < networkRetryDelaysMillis.size
                ) {
                    // DNS/连接类失败立即重试几乎必然复现，退避后才有恢复机会
                    delay(networkRetryDelaysMillis[networkAttempt].milliseconds)
                    networkAttempt += 1
                    continue
                }
                throw ComicOpenException(error.message ?: "页面下载失败", error)
            } catch (error: PageResponseException) {
                val retryable = error.code == 429 || error.code in 500..599
                if (priority == OnlinePageLoadPriority.FOREGROUND && retryable && !retry) {
                    val waitMillis = error.retryAfterMillis
                    when {
                        waitMillis != null -> {
                            if (waitMillis > MAX_RETRY_AFTER_MS) {
                                throw ComicOpenException("请求过于频繁，请稍后重试", httpCode = 429)
                            }
                            delay(waitMillis.milliseconds)
                        }

                        // A 429 without Retry-After still signals rate limiting; back off briefly
                        // instead of hammering the server again immediately.
                        error.code == 429 -> delay(DEFAULT_RETRY_BACKOFF_MS.milliseconds)

                        else -> {
                            // 5xx without Retry-After: the next attempt may succeed right away.
                        }
                    }
                    retry = true
                    continue
                }
                throw error
            }
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
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        calls -= call
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        calls -= call
                        val result = runCatching {
                            response.use {
                                if (!it.isSuccessful) {
                                    throw PageResponseException(
                                        code = it.code,
                                        retryAfterMillis =
                                            retryAfterMillis(it.header("Retry-After")),
                                    )
                                }
                                it.body.readPageBytes(PluginRuntimePolicy.MAX_IMAGE_BYTES)
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

    private fun retryAfterMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { seconds ->
            if (seconds < 0L) return null
            return seconds.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
        }
        return runCatching {
                val target =
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.between(Instant.now(), target).toMillis().coerceAtLeast(0L)
            }
            .getOrNull()
    }

    private data class PageTarget(
        val descriptor: PageDescriptor,
        val key: OnlinePageCacheKey,
    )

    private data class ChapterSource(
        val pages: List<PageDescriptor>,
        val cacheIdentity: OnlinePageCacheIdentity,
    )

    private class PageResponseException(
        val code: Int,
        val retryAfterMillis: Long?,
    ) : Exception() {
        fun toComicOpenException(): ComicOpenException =
            ComicOpenException("页面请求失败（HTTP $code）", httpCode = code)
    }

    private companion object {
        val DESCRIPTOR_REFRESH_CODES = setOf(401, 403, 404)
        const val MAX_RETRY_AFTER_MS = 30_000L
        const val DEFAULT_RETRY_BACKOFF_MS = 1_000L

        /** 前台网络类失败的退避序列：共 1 次原始尝试 + 2 次退避重试。 */
        val DEFAULT_NETWORK_RETRY_DELAYS_MILLIS = listOf(500L, 2_000L)
    }
}

internal fun onlinePageIdentities(
    sourceRevision: String,
    pages: List<PageDescriptor>,
): List<String> = pages.mapIndexed { index, page ->
    page.pageId?.let { pageId -> "id:${pageId.length}:$pageId" }
        ?: "revision-index:${sourceRevision.length}:$sourceRevision:$index"
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
