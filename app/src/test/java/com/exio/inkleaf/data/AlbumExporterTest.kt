package com.exio.inkleaf.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumExporterTest {
    @Test
    fun `suggested file name replaces unsafe characters and keeps unicode`() {
        assertEquals("旅行_夏天_.cbz", fileNameForTitle("  旅行:夏天?  "))
        assertEquals("Inkleaf.cbz", fileNameForTitle("..."))
    }

    @Test
    fun `comic info escapes title and marks selected page as front cover`() {
        val pages = listOf(page(id = "10", position = 0), page(id = "20", position = 1))

        val xml = comicInfoXml("A&B <album>", pages, coverPageId = "20")

        assertTrue(xml.contains("<Title>A&amp;B &lt;album&gt;</Title>"))
        assertTrue(xml.contains("<PageCount>2</PageCount>"))
        assertTrue(xml.contains("<Page Image=\"1\" Type=\"FrontCover\" />"))
        assertFalse(xml.contains("<Page Image=\"0\" Type=\"FrontCover\" />"))
    }

    @Test
    fun `cbz uses stable page order zero padded names and streamed bytes`() {
        val pages =
            listOf(
                page(id = "30", position = 2, extension = "PNG"),
                page(id = "10", position = 0, extension = "jpg"),
                page(id = "20", position = 1, extension = "webp"),
            )
        val bytesById = pages.associate { it.id to "page-${it.id}".toByteArray() }
        val output = ByteArrayOutputStream()

        writeCbz(output, "Album", coverPageId = "20", pages = pages) { page ->
            ByteArrayInputStream(bytesById.getValue(page.id))
        }

        val entries = readZip(output.toByteArray())
        assertEquals(
            listOf("ComicInfo.xml", "page-0001.jpg", "page-0002.webp", "page-0003.png"),
            entries.map { it.first },
        )
        assertArrayEquals(bytesById.getValue("10"), entries[1].second)
        assertArrayEquals(bytesById.getValue("20"), entries[2].second)
        assertArrayEquals(bytesById.getValue("30"), entries[3].second)
        assertTrue(
            entries
                .first()
                .second
                .toString(Charsets.UTF_8)
                .contains("Image=\"1\" Type=\"FrontCover\"")
        )
    }

    @Test
    fun `page names expand padding for very large albums and sanitize extension`() {
        assertEquals("page-0001.jpg", pageEntryName(0, 100, ""))
        assertEquals("page-00001.jpeg", pageEntryName(0, 10_000, ".Jp Eg"))
    }

    private fun page(
        id: String,
        position: Int,
        extension: String = "jpg",
    ) =
        ExportPage(
            id = id,
            position = position,
            relativePath = "albums/1/pages/$id.$extension",
            displayName = "$id.$extension",
            extension = extension,
        )

    private fun readZip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name to zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }
}
