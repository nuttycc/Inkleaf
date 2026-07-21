package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPositionResolverTest {
    @Test
    fun `unchanged revision uses stored page without identity lookup`() {
        var lookedUp = false
        val result = ReadingPositionResolver.resolve(
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            storedSourceRevision = "same",
            storedGlobalPage = 8,
            pageIdentity = "page-id",
            currentSourceRevision = "same",
            currentPageCount = 20,
            findPageByIdentity = {
                lookedUp = true
                2
            },
        )
        assertEquals(ReadingPositionResolution.Ready(8), result)
        assertTrue(!lookedUp)
    }

    @Test
    fun `zip and album remap surviving identity`() {
        for (type in listOf(BookSourceType.EXTERNAL_ARCHIVE, BookSourceType.CREATED_ALBUM)) {
            val result = ReadingPositionResolver.resolve(
                sourceType = type,
                storedSourceRevision = "before",
                storedGlobalPage = 8,
                pageIdentity = "page-id",
                currentSourceRevision = "after",
                currentPageCount = 20,
                findPageByIdentity = { 2 },
            )
            assertEquals(ReadingPositionResolution.Ready(2), result)
        }
    }

    @Test
    fun `pdf content change is always source changed`() {
        val result = ReadingPositionResolver.resolve(
            sourceType = BookSourceType.PDF_SERIES,
            storedSourceRevision = "before",
            storedGlobalPage = 8,
            pageIdentity = "pdf-page",
            currentSourceRevision = "after",
            currentPageCount = 20,
            findPageByIdentity = { 2 },
        )
        assertEquals(ReadingPositionResolution.SourceChanged(2), result)
    }

    @Test
    fun `lost identity clamps to approximate page`() {
        val result = ReadingPositionResolver.resolve(
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            storedSourceRevision = "before",
            storedGlobalPage = 99,
            pageIdentity = "gone",
            currentSourceRevision = "after",
            currentPageCount = 10,
            findPageByIdentity = { null },
        )
        assertEquals(ReadingPositionResolution.SourceChanged(9), result)
    }

    @Test
    fun `empty book is unavailable`() {
        val result = ReadingPositionResolver.resolve(
            sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            storedSourceRevision = "a",
            storedGlobalPage = 0,
            pageIdentity = null,
            currentSourceRevision = "a",
            currentPageCount = 0,
            findPageByIdentity = { null },
        )
        assertTrue(result is ReadingPositionResolution.Unavailable)
    }
}
