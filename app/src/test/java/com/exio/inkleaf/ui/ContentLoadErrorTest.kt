package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ComicOpenException
import com.exio.inkleaf.plugin.PluginErrorCode
import com.exio.inkleaf.plugin.PluginRpcError
import com.exio.inkleaf.plugin.PluginRpcException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLoadErrorTest {
    @Test
    fun `unknown host exception classifies as dns failure`() {
        val error =
            UnknownHostException(
                "Unable to resolve host \"ss.mangafunb.fun\": No address associated with hostname"
            )

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.DNS_UNRESOLVED, presentation.kind)
        assertEquals("无法连接内容源", presentation.message)
        assertTrue(presentation.retryable)
        assertTrue(presentation.technicalDetail.contains("ss.mangafunb.fun"))
    }

    @Test
    fun `wrapped unknown host cause is classified through the chain`() {
        val error =
            ComicOpenException(
                message =
                    "Unable to resolve host \"mapi.hotmangasf.com\": No address associated with hostname",
                cause = UnknownHostException("Unable to resolve host \"mapi.hotmangasf.com\""),
            )

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.DNS_UNRESOLVED, presentation.kind)
        assertTrue(presentation.technicalDetail.contains(ComicOpenException::class.java.simpleName))
    }

    @Test
    fun `socket timeout classifies as timeout`() {
        val error = ComicOpenException("timeout", cause = SocketTimeoutException("Read timed out"))

        assertEquals(ContentLoadErrorKind.TIMEOUT, error.toContentLoadError().kind)
        assertEquals("连接超时", error.toContentLoadError().message)
    }

    @Test
    fun `connect exception classifies as connection failed`() {
        val error = ComicOpenException("refused", cause = ConnectException("Connection refused"))

        assertEquals(ContentLoadErrorKind.CONNECTION_FAILED, error.toContentLoadError().kind)
    }

    @Test
    fun `ssl exception classifies as connection failed`() {
        val error = ComicOpenException("tls", cause = SSLException("Connection reset by peer"))

        assertEquals(ContentLoadErrorKind.CONNECTION_FAILED, error.toContentLoadError().kind)
    }

    @Test
    fun `http 429 message classifies as rate limited`() {
        val error = ComicOpenException("页面请求失败（HTTP 429）")

        assertEquals(ContentLoadErrorKind.RATE_LIMITED, error.toContentLoadError().kind)
    }

    @Test
    fun `http 404 message classifies as content missing and not retryable`() {
        val error = ComicOpenException("页面请求失败（HTTP 404）")

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.CONTENT_MISSING, presentation.kind)
        assertFalse(presentation.retryable)
    }

    @Test
    fun `http 5xx message classifies as service unavailable`() {
        val error = ComicOpenException("页面请求失败（HTTP 503）")

        assertEquals(ContentLoadErrorKind.HTTP_REJECTED, error.toContentLoadError().kind)
    }

    @Test
    fun `rate limit copy without code classifies as rate limited`() {
        val error = ComicOpenException("请求过于频繁，请稍后重试")

        assertEquals(ContentLoadErrorKind.RATE_LIMITED, error.toContentLoadError().kind)
    }

    @Test
    fun `rpc network error falls back to connection failed for plain io causes`() {
        val error =
            PluginRpcException(
                error =
                    PluginRpcError(
                        PluginErrorCode.NETWORK,
                        "unexpected end of stream",
                        retryable = true,
                    ),
                cause = java.io.IOException("unexpected end of stream"),
            )

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.CONNECTION_FAILED, presentation.kind)
        assertTrue(presentation.retryable)
    }

    @Test
    fun `rpc not found classifies as content missing`() {
        val error =
            PluginRpcException(error = PluginRpcError(PluginErrorCode.NOT_FOUND, "chapter gone"))

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.CONTENT_MISSING, presentation.kind)
        assertFalse(presentation.retryable)
    }

    @Test
    fun `rpc auth required is not retryable`() {
        val error =
            PluginRpcException(
                error = PluginRpcError(PluginErrorCode.AUTH_REQUIRED, "login required")
            )

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.PLUGIN, presentation.kind)
        assertFalse(presentation.retryable)
        assertTrue(presentation.technicalDetail.contains("login required"))
    }

    @Test
    fun `wrapped plugin not found classifies through the chain`() {
        val error =
            RuntimeException(
                "outer wrapper",
                PluginRpcException(PluginRpcError(PluginErrorCode.NOT_FOUND, "chapter gone")),
            )

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.CONTENT_MISSING, presentation.kind)
        assertFalse(presentation.retryable)
        assertTrue(presentation.technicalDetail.contains("outer wrapper"))
    }

    @Test
    fun `wrapped plugin auth required is not retryable`() {
        val error =
            RuntimeException(
                "outer wrapper",
                PluginRpcException(PluginRpcError(PluginErrorCode.AUTH_REQUIRED, "login required")),
            )

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.PLUGIN, presentation.kind)
        assertFalse(presentation.retryable)
    }

    @Test
    fun `wrapped plugin network error still inspects the cause chain`() {
        val error =
            RuntimeException(
                "outer wrapper",
                PluginRpcException(
                    error = PluginRpcError(PluginErrorCode.NETWORK, "request failed"),
                    cause = UnknownHostException("Unable to resolve host mapi.hotmangasf.com"),
                ),
            )

        assertEquals(ContentLoadErrorKind.DNS_UNRESOLVED, error.toContentLoadError().kind)
    }

    @Test
    fun `rpc timeout classifies as timeout`() {
        val error = PluginRpcException(error = PluginRpcError(PluginErrorCode.TIMEOUT, "timed out"))

        assertEquals(ContentLoadErrorKind.TIMEOUT, error.toContentLoadError().kind)
    }

    @Test
    fun `rpc plugin failure classifies as plugin problem`() {
        val error =
            PluginRpcException(error = PluginRpcError(PluginErrorCode.PLUGIN_ERROR, "script threw"))

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.PLUGIN, presentation.kind)
        assertTrue(presentation.retryable)
    }

    @Test
    fun `offline flag takes precedence over the failure cause`() {
        val error =
            ComicOpenException("Unable to resolve host", cause = UnknownHostException("no address"))

        val presentation = error.toContentLoadError(isNetworkAvailable = false)

        assertEquals(ContentLoadErrorKind.NO_NETWORK, presentation.kind)
        assertEquals("无网络连接", presentation.message)
    }

    @Test
    fun `unknown failures keep the original message and the technical chain`() {
        val error = ComicOpenException("页面图像超过大小限制")

        val presentation = error.toContentLoadError()

        assertEquals(ContentLoadErrorKind.UNKNOWN, presentation.kind)
        assertEquals("页面图像超过大小限制", presentation.message)
        assertNull(presentation.hint)
        assertTrue(presentation.technicalDetail.contains("页面图像超过大小限制"))
    }

    @Test
    fun `structured http code classifies even when the message is reworded`() {
        val rateLimited = ComicOpenException("自定义文案", httpCode = 429)
        val missing = ComicOpenException("自定义文案", httpCode = 404)

        assertEquals(ContentLoadErrorKind.RATE_LIMITED, rateLimited.toContentLoadError().kind)
        val missingPresentation = missing.toContentLoadError()
        assertEquals(ContentLoadErrorKind.CONTENT_MISSING, missingPresentation.kind)
        assertFalse(missingPresentation.retryable)
    }

    @Test
    fun `plugin http code scans the chain for rate limiting`() {
        val error =
            PluginRpcException(error = PluginRpcError(PluginErrorCode.HTTP, "页面请求失败（HTTP 429）"))

        assertEquals(ContentLoadErrorKind.RATE_LIMITED, error.toContentLoadError().kind)
    }

    @Test
    fun `self cause cycles terminate`() {
        // Throwable.initCause 禁止自引用，这里用 getCause 覆盖模拟真实世界中出现过的环形 cause
        val error =
            object : UnknownHostException("loop") {
                override val cause: Throwable
                    get() = this
            }

        assertEquals(ContentLoadErrorKind.DNS_UNRESOLVED, error.toContentLoadError().kind)
    }
}
