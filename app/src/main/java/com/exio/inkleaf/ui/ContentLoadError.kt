package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginErrorCode
import com.exio.inkleaf.plugin.PluginRpcException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** 在线内容加载失败的稳定分类。UI 依据它选择文案与恢复动作， 原始异常文本只进入 [ContentLoadError.technicalDetail]，不再直接展示。 */
enum class ContentLoadErrorKind {
    NO_NETWORK,
    DNS_UNRESOLVED,
    TIMEOUT,
    CONNECTION_FAILED,
    RATE_LIMITED,
    HTTP_REJECTED,
    CONTENT_MISSING,
    PLUGIN,
    UNKNOWN,
}

/** 加载失败的界面呈现：主文案 + 可选建议 + 供"详细信息"复制的技术细节。 */
data class ContentLoadError(
    val kind: ContentLoadErrorKind,
    val message: String,
    val hint: String? = null,
    val technicalDetail: String,
    val retryable: Boolean = true,
)

/** 本地/未分类错误的直通呈现：message 已是可读文案，原样作为标题展示。 */
fun plainContentLoadError(message: String): ContentLoadError =
    ContentLoadError(
        kind = ContentLoadErrorKind.UNKNOWN,
        message = message,
        technicalDetail = message,
    )

/**
 * 把任意加载失败异常归类为界面可呈现的 [ContentLoadError]。
 *
 * 分类走完整的 cause 链：在线页面的原始异常（如 OkHttp 的 UnknownHostException） 通常被包在
 * [com.exio.inkleaf.data.ComicOpenException] 或 PluginRpcException 内层。 [isNetworkAvailable]
 * 由调用方传入设备连通性预检结果，无网络时优先给出无网络文案， 避免让用户读到底层 DNS/连接错误。
 */
fun Throwable.toContentLoadError(isNetworkAvailable: Boolean = true): ContentLoadError {
    if (!isNetworkAvailable) return presentation(ContentLoadErrorKind.NO_NETWORK, this)
    if (this is PluginRpcException) {
        return when (error.code) {
            PluginErrorCode.NOT_FOUND ->
                presentation(ContentLoadErrorKind.CONTENT_MISSING, this, retryable = false)
            PluginErrorCode.AUTH_REQUIRED ->
                presentation(
                    ContentLoadErrorKind.PLUGIN,
                    this,
                    message = "内容源需要登录后使用",
                    hint = "在内容源设置中登录后再试",
                    retryable = false,
                )
            PluginErrorCode.PLUGIN_DISABLED ->
                presentation(
                    ContentLoadErrorKind.PLUGIN,
                    this,
                    message = "内容源插件已被停用",
                    hint = "重新启用插件后再试",
                    retryable = false,
                )
            PluginErrorCode.TIMEOUT -> presentation(ContentLoadErrorKind.TIMEOUT, this)
            PluginErrorCode.HTTP -> presentation(ContentLoadErrorKind.HTTP_REJECTED, this)
            PluginErrorCode.NETWORK -> classifyChain(this).withConnectionFallback()
            else -> presentation(ContentLoadErrorKind.PLUGIN, this)
        }
    }
    return classifyChain(this)
}

private val HTTP_CODE = Regex("HTTP (\\d{3})")
private const val MAX_CAUSE_CHAIN = 10

/** 沿 cause 链按异常类型分类；类型未命中时退回消息文本信号。 */
private fun classifyChain(error: Throwable): ContentLoadError {
    val chain = causeChain(error)
    for (node in chain) {
        when (node) {
            is UnknownHostException ->
                return presentation(ContentLoadErrorKind.DNS_UNRESOLVED, error)
            is SocketTimeoutException,
            is TimeoutException -> return presentation(ContentLoadErrorKind.TIMEOUT, error)
            is ConnectException ->
                return presentation(ContentLoadErrorKind.CONNECTION_FAILED, error)
            is SSLException -> return presentation(ContentLoadErrorKind.CONNECTION_FAILED, error)
        }
    }
    val messages = chain.mapNotNull { it.message }
    if (messages.any { it.contains("请求过于频繁") }) {
        return presentation(ContentLoadErrorKind.RATE_LIMITED, error)
    }
    val httpCode = messages.firstNotNullOfOrNull { message ->
        HTTP_CODE.find(message)?.groupValues?.get(1)?.toIntOrNull()
    }
    if (httpCode != null) {
        val kind =
            when {
                httpCode == 429 -> ContentLoadErrorKind.RATE_LIMITED
                httpCode == 404 -> ContentLoadErrorKind.CONTENT_MISSING
                else -> ContentLoadErrorKind.HTTP_REJECTED
            }
        return presentation(kind, error, retryable = kind != ContentLoadErrorKind.CONTENT_MISSING)
    }
    return presentation(ContentLoadErrorKind.UNKNOWN, error)
}

private fun ContentLoadError.withConnectionFallback(): ContentLoadError =
    if (kind == ContentLoadErrorKind.UNKNOWN) {
        copy(
            kind = ContentLoadErrorKind.CONNECTION_FAILED,
            message = "连接失败",
            hint = "站点可能拒绝了当前网络的访问，可尝试更换网络或代理",
        )
    } else {
        this
    }

private fun causeChain(error: Throwable): List<Throwable> {
    val seen = HashSet<Throwable>()
    val chain = ArrayList<Throwable>()
    var current: Throwable? = error
    while (current != null && chain.size < MAX_CAUSE_CHAIN && seen.add(current)) {
        chain += current
        current = current.cause?.takeIf { it !== current }
    }
    return chain
}

private fun technicalDetail(error: Throwable): String =
    causeChain(error).joinToString(separator = "\n") { node ->
        val message = node.message.orEmpty()
        if (message.isBlank()) node.javaClass.name else "${node.javaClass.name}: $message"
    }

private fun presentation(
    kind: ContentLoadErrorKind,
    error: Throwable,
    retryable: Boolean = true,
    message: String? = null,
    hint: String? = null,
): ContentLoadError =
    ContentLoadError(
        kind = kind,
        message = message ?: kind.defaultMessage(),
        hint = hint ?: kind.defaultHint(),
        technicalDetail = technicalDetail(error),
        retryable = retryable,
    )

private fun ContentLoadErrorKind.defaultMessage(): String =
    when (this) {
        ContentLoadErrorKind.NO_NETWORK -> "无网络连接"
        ContentLoadErrorKind.DNS_UNRESOLVED -> "无法连接内容源"
        ContentLoadErrorKind.TIMEOUT -> "连接超时"
        ContentLoadErrorKind.CONNECTION_FAILED -> "连接失败"
        ContentLoadErrorKind.RATE_LIMITED -> "请求过于频繁"
        ContentLoadErrorKind.HTTP_REJECTED -> "服务暂时不可用"
        ContentLoadErrorKind.CONTENT_MISSING -> "内容不存在"
        ContentLoadErrorKind.PLUGIN -> "内容源出现问题"
        ContentLoadErrorKind.UNKNOWN -> "加载失败"
    }

private fun ContentLoadErrorKind.defaultHint(): String? =
    when (this) {
        ContentLoadErrorKind.NO_NETWORK -> "请检查设备网络后重试"
        ContentLoadErrorKind.DNS_UNRESOLVED -> "站点域名可能被当前网络屏蔽或已更换，可尝试更换网络或代理，或更新插件"
        ContentLoadErrorKind.TIMEOUT -> "网络不稳定或站点响应缓慢，请重试"
        ContentLoadErrorKind.CONNECTION_FAILED -> "站点可能拒绝了当前网络的访问，可尝试更换网络或代理"
        ContentLoadErrorKind.RATE_LIMITED -> "站点正在限流，请稍等片刻后重试"
        ContentLoadErrorKind.HTTP_REJECTED -> "站点返回了错误响应，请稍后重试"
        ContentLoadErrorKind.CONTENT_MISSING -> "章节可能已下架，或插件需要更新"
        ContentLoadErrorKind.PLUGIN -> "尝试更新或重新启用插件后再试"
        ContentLoadErrorKind.UNKNOWN -> null
    }
