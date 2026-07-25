package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerLogicTest {
    @Test
    fun `PDF recognition accepts MIME or extension`() {
        assertTrue(LibraryScanner.isPdf("chapter.bin", "application/pdf"))
        assertTrue(LibraryScanner.isPdf("chapter.PDF", "application/octet-stream"))
        assertEquals(false, LibraryScanner.isPdf("chapter.zip", "application/zip"))
    }

    @Test
    fun `PDF ordering ignores folders until names tie`() {
        val files =
            listOf(
                scanned("101.pdf", "part-a/101.pdf", "101"),
                scanned("2.pdf", "part-z/2.pdf", "2"),
                scanned("1.pdf", "part-b/1.pdf", "1b"),
                scanned("1.pdf", "part-a/1.pdf", "1a"),
            )

        val sorted = LibraryScanner.sortPdfs(files)

        assertEquals(
            listOf("part-a/1.pdf", "part-b/1.pdf", "part-z/2.pdf", "part-a/101.pdf"),
            sorted.map { it.relativePath },
        )
    }

    @Test
    fun `soft threshold requests confirmation and hard threshold wins`() {
        val soft = LibraryScanner.SOFT_SCAN_THRESHOLDS
        val atSoft = LibraryScanner.ScanMetrics(pdfCount = soft.pdfCount)
        val atHard =
            LibraryScanner.ScanMetrics(pdfCount = LibraryScanner.HARD_SCAN_THRESHOLDS.pdfCount + 1)

        assertEquals(
            LibraryScanner.ScanStopReason.CONFIRMATION_REQUIRED to LibraryScanner.ScanLimit.PDFS,
            LibraryScanner.evaluateLimit(atSoft, soft),
        )
        assertEquals(
            LibraryScanner.ScanStopReason.HARD_LIMIT_REACHED to LibraryScanner.ScanLimit.PDFS,
            LibraryScanner.evaluateLimit(atHard, null),
        )
        assertNull(LibraryScanner.evaluateLimit(LibraryScanner.ScanMetrics(), soft))
    }

    private fun scanned(name: String, path: String, key: String) =
        LibraryScanner.ScannedFile(
            uri = "content://test/$key",
            fileKey = key,
            displayName = name,
            relativePath = path,
        )
}
