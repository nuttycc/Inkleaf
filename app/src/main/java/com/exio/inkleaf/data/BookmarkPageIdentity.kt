package com.exio.inkleaf.data

import java.util.zip.ZipEntry

/** Source-specific page identity tokens used by bookmarks and reader caches. */
internal object BookmarkPageIdentity {
    fun zip(entryName: String, crc: Long, size: Long): String =
        "zip-v1:${ReaderPageCacheKey.sourceRevision(listOf(entryName, crc.toString(), size.toString()))}"

    fun zip(entry: ZipEntry): String = zip(entry.name, entry.crc, entry.size)

    fun findZipPage(entries: List<ZipEntry>, identity: String): Int? =
        entries.indexOfFirst { zip(it) == identity }.takeIf { it >= 0 }

    fun pdf(chapterFileKey: String, pageIndex: Int): String {
        require(pageIndex >= 0)
        val chapterToken = ReaderPageCacheKey.sourceRevision(listOf("pdf-chapter", chapterFileKey))
        return "pdf-v1:$pageIndex:$chapterToken"
    }

    fun pdfLocalPage(identity: String): Int? {
        if (!identity.startsWith(PDF_PREFIX)) return null
        return identity.substringAfter(PDF_PREFIX).substringBefore(':').toIntOrNull()?.takeIf {
            it >= 0
        }
    }

    private const val PDF_PREFIX = "pdf-v1:"
}
