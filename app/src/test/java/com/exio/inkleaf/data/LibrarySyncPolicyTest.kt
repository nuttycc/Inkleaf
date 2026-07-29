package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncPolicyTest {
    @Test
    fun `external archives persist an exclusion regardless of folder ownership`() {
        assertTrue(shouldPersistLibraryExclusion(BookSourceType.EXTERNAL_ARCHIVE))
        assertFalse(shouldPersistLibraryExclusion(BookSourceType.PDF_SERIES))
        assertFalse(shouldPersistLibraryExclusion(BookSourceType.CREATED_ALBUM))
    }

    @Test
    fun `excluded scanned file is not a discovery candidate`() {
        val candidates =
            discoverableLibraryFileKeys(
                scannedKeys = listOf("kept.cbz", "removed.cbz"),
                existingKeys = setOf("kept.cbz"),
                excludedKeys = setOf("removed.cbz"),
            )

        assertEquals(emptySet<String>(), candidates)
    }

    @Test
    fun `new scanned file remains a discovery candidate`() {
        val candidates =
            discoverableLibraryFileKeys(
                scannedKeys = listOf("new.cbz"),
                existingKeys = emptySet(),
                excludedKeys = emptySet(),
            )

        assertEquals(setOf("new.cbz"), candidates)
    }

    @Test
    fun `duplicate scanned identities collapse to one candidate`() {
        val candidates =
            discoverableLibraryFileKeys(
                scannedKeys = listOf("same.cbz", "same.cbz"),
                existingKeys = emptySet(),
                excludedKeys = emptySet(),
            )

        assertEquals(setOf("same.cbz"), candidates)
    }
}
