package com.exio.inkleaf.data

import java.util.zip.ZipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkPageIdentityTest {
    @Test
    fun `zip identity follows an entry when archive order changes`() {
        val target = zipEntry("chapter/010.jpg", crc = 1234L, size = 8192L)
        val identity = BookmarkPageIdentity.zip(target)
        val reordered =
            listOf(
                zipEntry("chapter/001.jpg", crc = 1L, size = 100L),
                zipEntry("chapter/020.jpg", crc = 2L, size = 200L),
                target,
            )

        assertEquals(2, BookmarkPageIdentity.findZipPage(reordered, identity))
    }

    @Test
    fun `zip identity changes when page content is replaced`() {
        val original = BookmarkPageIdentity.zip("chapter/010.jpg", crc = 1234L, size = 8192L)
        val replacement = BookmarkPageIdentity.zip("chapter/010.jpg", crc = 9876L, size = 9000L)

        assertNotEquals(original, replacement)
    }

    @Test
    fun `zip lookup distinguishes duplicate paths with different content`() {
        val original = zipEntry("chapter/page.jpg", crc = 1234L, size = 8192L)
        val replacement = zipEntry("chapter/page.jpg", crc = 9876L, size = 9000L)

        assertEquals(
            1,
            BookmarkPageIdentity.findZipPage(
                entries = listOf(replacement, original),
                identity = BookmarkPageIdentity.zip(original),
            ),
        )
        assertNull(
            BookmarkPageIdentity.findZipPage(
                entries = listOf(replacement),
                identity = BookmarkPageIdentity.zip(original),
            )
        )
    }

    @Test
    fun `pdf identity combines stable chapter and local page`() {
        val page = BookmarkPageIdentity.pdf("chapter-file-key", pageIndex = 17)

        assertEquals(17, BookmarkPageIdentity.pdfLocalPage(page))
        assertNotEquals(page, BookmarkPageIdentity.pdf("other-chapter", pageIndex = 17))
        assertNotEquals(page, BookmarkPageIdentity.pdf("chapter-file-key", pageIndex = 18))
        assertNull(BookmarkPageIdentity.pdfLocalPage("album-page-id"))
    }

    private fun zipEntry(name: String, crc: Long, size: Long) =
        ZipEntry(name).apply {
            this.crc = crc
            this.size = size
        }
}
