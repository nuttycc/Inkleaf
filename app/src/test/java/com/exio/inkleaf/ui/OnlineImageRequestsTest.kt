package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PageImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineImageRequestsTest {
    @Test
    fun `cache key canonicalizes URL and header order`() {
        val first =
            PageImage(
                url = "https://EXAMPLE.com:443/image.jpg",
                headers =
                    linkedMapOf(
                        "Authorization" to "Bearer private-token",
                        "Cookie" to "session=private-cookie",
                    ),
                referer = "https://reader.example/chapter/1",
            )
        val reordered =
            PageImage(
                url = "https://example.com/image.jpg",
                headers =
                    linkedMapOf(
                        "cookie" to "session=private-cookie",
                        "authorization" to "Bearer private-token",
                    ),
                referer = "https://reader.example/chapter/1",
            )

        assertEquals(first.cacheKey(), reordered.cacheKey())
    }

    @Test
    fun `cache key changes with effective request context and hides secrets`() {
        val image =
            PageImage(
                url = "https://example.com/image.jpg",
                headers =
                    mapOf(
                        "Authorization" to "Bearer private-token",
                        "Cookie" to "session=private-cookie",
                    ),
                referer = "https://reader.example/chapter/1",
            )
        val key = image.cacheKey()

        assertNotEquals(key, image.copy(url = "https://example.com/other.jpg").cacheKey())
        assertNotEquals(
            key,
            image.copy(headers = image.headers + ("X-Reader" to "compact")).cacheKey(),
        )
        assertNotEquals(key, image.copy(referer = "https://reader.example/chapter/2").cacheKey())
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("private-token"))
        assertFalse(key.contains("private-cookie"))
        assertFalse(key.contains("reader.example"))
    }
}
