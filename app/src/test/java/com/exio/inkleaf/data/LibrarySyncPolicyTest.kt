package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncPolicyTest {
    @Test
    fun `external archives persist an exclusion regardless of folder ownership`() {
        assertTrue(shouldPersistLibraryExclusion(BookSourceType.EXTERNAL_ARCHIVE))
        assertFalse(shouldPersistLibraryExclusion(BookSourceType.IMPORTED_ARCHIVE))
        assertFalse(shouldPersistLibraryExclusion(BookSourceType.PDF_SERIES))
        assertFalse(shouldPersistLibraryExclusion(BookSourceType.CREATED_ALBUM))
    }

    @Test
    fun `imported comic file name is stable and safe`() {
        val first = importedComicFileName("content://provider/temporary/123")
        val second = importedComicFileName("content://provider/temporary/123")

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}\\.cbz")))
    }

    @Test
    fun `document identity decodes percent-encoded SAF URIs`() {
        assertEquals(
            "$AUTHORITY:primary:Download/1DMP/book.cbz",
            libraryDocumentIdentity(documentUri("primary:Download/1DMP/book.cbz")),
        )
        assertNull(libraryDocumentIdentity(treeUri()))
        assertNull(libraryDocumentIdentity("file:///tmp/book.cbz"))
    }

    @Test
    fun `changed key with the same document uri rewrites identity instead of marking missing`() {
        val documentId = "primary:Download/1DMP/book.cbz"
        val uri = documentUri(documentId)

        val diff =
            computeLibrarySyncDiff(
                scanned = listOf(LibraryScanEntry(uri = uri, fileKey = safKey(documentId))),
                existing =
                    listOf(
                        LibraryExistingEntry(
                            id = 1,
                            uri = uri,
                            fileKey = "stat:252:1",
                            isMissing = false,
                        )
                    ),
                excludedKeys = emptySet(),
            )

        assertEquals(emptyList<LibraryScanEntry>(), diff.newFiles)
        assertEquals(
            listOf(LibraryIdentityUpdate(id = 1, uri = uri, fileKey = safKey(documentId))),
            diff.identityUpdates,
        )
        assertEquals(emptyList<Long>(), diff.toMarkMissing)
        assertEquals(emptyList<Long>(), diff.restored)
    }

    @Test
    fun `previously missing rows recover when the scan uses new keys`() {
        val books =
            listOf("a.cbz", "b.cbz").mapIndexed { index, name ->
                val documentId = "primary:Download/1DMP/$name"
                LibraryExistingEntry(
                    id = index + 1L,
                    uri = documentUri(documentId),
                    fileKey = "stat:252:${index + 1}",
                    isMissing = true,
                )
            }

        val diff =
            computeLibrarySyncDiff(
                scanned =
                    books.map {
                        LibraryScanEntry(
                            uri = it.uri,
                            fileKey = "saf:${libraryDocumentIdentity(it.uri)}",
                        )
                    },
                existing = books,
                excludedKeys = emptySet(),
            )

        assertEquals(emptyList<LibraryScanEntry>(), diff.newFiles)
        assertEquals(books.map { it.id }, diff.restored)
        assertEquals(books.size, diff.identityUpdates.size)
        assertEquals(emptyList<Long>(), diff.toMarkMissing)
    }

    @Test
    fun `file absent from the scan is marked missing`() {
        val missingDocument = "primary:Download/1DMP/missing.cbz"
        val foundDocument = "primary:Download/1DMP/found.cbz"

        val diff =
            computeLibrarySyncDiff(
                scanned =
                    listOf(
                        LibraryScanEntry(
                            uri = documentUri(foundDocument),
                            fileKey = safKey(foundDocument),
                        )
                    ),
                existing =
                    listOf(
                        LibraryExistingEntry(
                            id = 1,
                            uri = documentUri(missingDocument),
                            fileKey = safKey(missingDocument),
                            isMissing = false,
                        )
                    ),
                excludedKeys = emptySet(),
            )

        assertEquals(listOf(1L), diff.toMarkMissing)
        assertEquals(emptyList<Long>(), diff.restored)
        assertEquals(1, diff.newFiles.size)
    }

    @Test
    fun `old and new keys for one document never produce a duplicate row`() {
        val documentId = "primary:Download/1DMP/book.cbz"
        val uri = documentUri(documentId)

        val diff =
            computeLibrarySyncDiff(
                scanned = listOf(LibraryScanEntry(uri = uri, fileKey = safKey(documentId))),
                existing =
                    listOf(
                        LibraryExistingEntry(1, uri, "stat:252:1", isMissing = false),
                        LibraryExistingEntry(2, uri, safKey(documentId), isMissing = false),
                    ),
                excludedKeys = emptySet(),
            )

        assertEquals(emptyList<LibraryScanEntry>(), diff.newFiles)
        assertEquals(emptyList<LibraryIdentityUpdate>(), diff.identityUpdates)
        assertEquals(emptyList<Long>(), diff.toMarkMissing)
    }

    @Test
    fun `new document becomes a discovery candidate`() {
        val documentId = "primary:Download/1DMP/new.cbz"

        val diff =
            computeLibrarySyncDiff(
                scanned =
                    listOf(
                        LibraryScanEntry(
                            uri = documentUri(documentId),
                            fileKey = safKey(documentId),
                        )
                    ),
                existing = emptyList(),
                excludedKeys = emptySet(),
            )

        assertEquals(1, diff.newFiles.size)
        assertEquals(safKey(documentId), diff.newFiles.single().fileKey)
    }

    @Test
    fun `excluded document is not a discovery candidate`() {
        val documentId = "primary:Download/1DMP/deleted.cbz"

        val diff =
            computeLibrarySyncDiff(
                scanned =
                    listOf(
                        LibraryScanEntry(
                            uri = documentUri(documentId),
                            fileKey = safKey(documentId),
                        )
                    ),
                existing = emptyList(),
                excludedKeys = setOf(safKey(documentId)),
            )

        assertEquals(emptyList<LibraryScanEntry>(), diff.newFiles)
    }

    @Test
    fun `duplicate scanned identities collapse to one candidate`() {
        val entry =
            LibraryScanEntry(
                uri = documentUri("primary:Download/1DMP/book.cbz"),
                fileKey = "saf:key",
            )

        val diff =
            computeLibrarySyncDiff(
                scanned = listOf(entry, entry),
                existing = emptyList(),
                excludedKeys = emptySet(),
            )

        assertEquals(listOf(entry), diff.newFiles)
    }

    private fun safKey(documentId: String): String = "saf:$AUTHORITY:$documentId"

    private fun documentUri(documentId: String): String =
        "${treeUri()}/document/${encodePathSegment(documentId)}"

    private fun treeUri(): String = "content://$AUTHORITY/tree/${encodePathSegment(TREE_ID)}"

    private fun encodePathSegment(value: String): String =
        value.replace(":", "%3A").replace("/", "%2F")

    private companion object {
        const val AUTHORITY = "com.android.providers.downloads.documents"
        const val TREE_ID = "primary:Download/1DMP"
    }
}
