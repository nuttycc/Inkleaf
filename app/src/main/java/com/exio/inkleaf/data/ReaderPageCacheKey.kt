package com.exio.inkleaf.data

import java.security.MessageDigest

/** Builds image memory-cache keys without treating mutable album indexes as stable identities. */
internal object ReaderPageCacheKey {
    fun forPage(cacheKeyPrefix: String, page: Int, pageIdentity: String?): String {
        val identity = pageIdentity?.takeIf { it.isNotBlank() } ?: page.toString()
        return "$cacheKeyPrefix#$identity"
    }

    fun thumbnailFileName(page: Int, pageIdentity: String?): String {
        val identity = pageIdentity?.takeIf { it.isNotBlank() }
            ?: return "$page.jpg"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        val token = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        return "id-$token.jpg"
    }

    private const val HEX = "0123456789abcdef"
}
