package com.exio.inkleaf.data

import java.security.MessageDigest

/** Builds reader cache keys without treating mutable album indexes as stable identities. */
internal object ReaderPageCacheKey {
    fun forPage(
        cacheKeyPrefix: String,
        page: Int,
        pageIdentity: String?,
        sourceRevision: String? = null,
    ): String {
        val identity = pageIdentity?.takeIf { it.isNotBlank() } ?: page.toString()
        val revision = sourceRevision?.takeIf { it.isNotBlank() }
        return if (revision == null) {
            "$cacheKeyPrefix#$identity"
        } else {
            "$cacheKeyPrefix@$revision#$identity"
        }
    }

    /** Produces a compact revision token without joining large page lists in memory. */
    fun sourceRevision(parts: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return hex(digest.digest())
    }

    fun thumbnailFileName(page: Int, pageIdentity: String?): String {
        val identity = pageIdentity?.takeIf { it.isNotBlank() } ?: return "$page.jpg"
        val token = sha256Hex(identity.toByteArray(Charsets.UTF_8))
        return "id-$token.jpg"
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }

    private const val HEX = "0123456789abcdef"
}
