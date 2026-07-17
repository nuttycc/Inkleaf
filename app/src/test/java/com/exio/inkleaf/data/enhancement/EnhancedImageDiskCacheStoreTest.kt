package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

class EnhancedImageDiskCacheStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pathContainsVersionAndIdentityAndHashesCacheKey() {
        val fixture = fixture()

        assertEquals(
            File(
                fixture.cacheDir,
                "ai_enhanced_images/v1/42/model-2x/source-revision/" +
                        "49dc1741e4eea0ad65e4a4de9f2d7492a65d8998cf311e4295c6c6201865999e.png",
            ),
            fixture.store.transientFile(ENTRY),
        )
    }

    @Test
    fun readPrefersPinnedAndTouchesTheHit() = runBlocking {
        val fixture = fixture(now = { 9_999L })
        fixture.store.writeTransient(ENTRY, "transient".encodeToByteArray())
        fixture.store.writePinned(ENTRY, "pinned".encodeToByteArray())
        fixture.store.pinnedFile(ENTRY).setLastModified(1L)

        assertArrayEquals("pinned".encodeToByteArray(), fixture.store.read(ENTRY))
        assertEquals(9_999L, fixture.store.pinnedFile(ENTRY).lastModified())
    }

    @Test
    fun corruptPinnedFileIsDeletedAndReadFallsBackToTransient() = runBlocking {
        val fixture = fixture()
        fixture.store.writeTransient(ENTRY, "transient".encodeToByteArray())
        fixture.store.pinnedFile(ENTRY).apply {
            parentFile?.mkdirs()
            writeText("corrupt")
        }

        assertArrayEquals("transient".encodeToByteArray(), fixture.store.read(ENTRY))
        assertFalse(fixture.store.pinnedFile(ENTRY).exists())
    }

    @Test
    fun failedEncodingDoesNotReplaceExistingEntryOrLeaveTemporaryFiles() = runBlocking {
        val fixture = fixture()
        fixture.store.writeTransient(ENTRY, "existing".encodeToByteArray())
        val failingStore = EnhancedImageDiskCacheStore(
            cacheDir = fixture.cacheDir,
            filesDir = fixture.filesDir,
            codec = ByteArrayCodec(failEncoding = true),
        )

        assertFalse(failingStore.writeTransient(ENTRY, "new".encodeToByteArray()))
        assertArrayEquals("existing".encodeToByteArray(), fixture.store.read(ENTRY))
        assertTrue(
            fixture.store.transientFile(ENTRY).parentFile
                ?.listFiles()
                ?.none { it.name.endsWith(".tmp") } == true,
        )
    }

    @Test
    fun promoteMovesTransientEntryToPinnedStorage() = runBlocking {
        val fixture = fixture()
        fixture.store.writeTransient(ENTRY, "page".encodeToByteArray())

        assertTrue(fixture.store.promoteToPinned(ENTRY))
        assertTrue(fixture.store.containsPinned(ENTRY))
        assertFalse(fixture.store.transientFile(ENTRY).exists())
        assertArrayEquals("page".encodeToByteArray(), fixture.store.read(ENTRY))
    }

    @Test
    fun containsPinnedRejectsMissingAndEmptyFiles() = runBlocking {
        val fixture = fixture()

        assertFalse(fixture.store.containsPinned(ENTRY))
        fixture.store.pinnedFile(ENTRY).apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        assertFalse(fixture.store.containsPinned(ENTRY))
    }

    @Test
    fun containsPinnedDeletesNonEmptyCorruptFile() = runBlocking {
        val fixture = fixture()
        fixture.store.pinnedFile(ENTRY).apply {
            parentFile?.mkdirs()
            writeText("corrupt")
        }

        assertFalse(fixture.store.containsPinned(ENTRY))
        assertFalse(fixture.store.pinnedFile(ENTRY).exists())
    }

    @Test
    fun promoteReplacesCorruptPinnedFileWithValidTransientEntry() = runBlocking {
        val fixture = fixture()
        fixture.store.writeTransient(ENTRY, "valid".encodeToByteArray())
        fixture.store.pinnedFile(ENTRY).apply {
            parentFile?.mkdirs()
            writeText("corrupt")
        }

        assertTrue(fixture.store.promoteToPinned(ENTRY))
        assertFalse(fixture.store.transientFile(ENTRY).exists())
        assertArrayEquals("valid".encodeToByteArray(), fixture.store.read(ENTRY))
    }

    @Test
    fun promoteDeletesCorruptTransientEntryInsteadOfPinningIt() = runBlocking {
        val fixture = fixture()
        fixture.store.transientFile(ENTRY).apply {
            parentFile?.mkdirs()
            writeText("corrupt")
        }

        assertFalse(fixture.store.promoteToPinned(ENTRY))
        assertFalse(fixture.store.transientFile(ENTRY).exists())
        assertFalse(fixture.store.pinnedFile(ENTRY).exists())
    }

    @Test
    fun transientBudgetDeletesOldestFilesWithoutTouchingPinnedStorage() = runBlocking {
        val fixture = fixture()
        val oldest = ENTRY.copy(cacheKey = "oldest")
        val middle = ENTRY.copy(cacheKey = "middle")
        val newest = ENTRY.copy(cacheKey = "newest")
        fixture.store.writeTransient(oldest, byteArrayOf(1, 1, 1))
        fixture.store.writeTransient(middle, byteArrayOf(2, 2, 2))
        fixture.store.writeTransient(newest, byteArrayOf(3, 3, 3))
        fixture.store.writePinned(oldest, byteArrayOf(9, 9, 9))
        fixture.store.transientFile(oldest).setLastModified(100L)
        fixture.store.transientFile(middle).setLastModified(200L)
        fixture.store.transientFile(newest).setLastModified(300L)

        fixture.store.enforceTransientBudget(maxBytes = 3L)

        assertFalse(fixture.store.transientFile(oldest).exists())
        assertFalse(fixture.store.transientFile(middle).exists())
        assertTrue(fixture.store.transientFile(newest).exists())
        assertTrue(fixture.store.pinnedFile(oldest).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun transientBudgetRequiresPositiveLimit() = runBlocking {
        fixture().store.enforceTransientBudget(maxBytes = 0L)
    }

    @Test
    fun deleteComicRemovesBothStorageTiers() = runBlocking {
        val fixture = fixture()
        fixture.store.writeTransient(ENTRY, "transient".encodeToByteArray())
        fixture.store.writePinned(ENTRY.copy(cacheKey = "other"), "pinned".encodeToByteArray())

        fixture.store.deleteComic(ENTRY.comicId)

        assertFalse(fixture.store.transientFile(ENTRY).exists())
        assertFalse(fixture.store.pinnedFile(ENTRY.copy(cacheKey = "other")).exists())
    }

    @Test
    fun deleteModelRemovesItAcrossComicsWithoutTouchingOtherModels() = runBlocking {
        val fixture = fixture()
        val secondComic = ENTRY.copy(comicId = 43L)
        val otherModel = ENTRY.copy(modelId = "other-model")
        fixture.store.writeTransient(ENTRY, "one".encodeToByteArray())
        fixture.store.writePinned(secondComic, "two".encodeToByteArray())
        fixture.store.writePinned(otherModel, "keep".encodeToByteArray())

        fixture.store.deleteModel(ENTRY.modelId)

        assertNull(fixture.store.read(ENTRY))
        assertNull(fixture.store.read(secondComic))
        assertArrayEquals("keep".encodeToByteArray(), fixture.store.read(otherModel))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathSegmentsRejectDirectoryTraversal() {
        fixture().store.transientFile(ENTRY.copy(modelId = "../escape"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathSegmentsRejectParentDirectoryAlias() {
        fixture().store.transientFile(ENTRY.copy(sourceRevision = ".."))
    }

    private fun fixture(now: () -> Long = { 5_000L }): Fixture {
        val root = temporaryFolder.newFolder()
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val filesDir = File(root, "files").apply { mkdirs() }
        return Fixture(
            cacheDir = cacheDir,
            filesDir = filesDir,
            store = EnhancedImageDiskCacheStore(
                cacheDir = cacheDir,
                filesDir = filesDir,
                codec = ByteArrayCodec(),
                now = now,
            ),
        )
    }

    private data class Fixture(
        val cacheDir: File,
        val filesDir: File,
        val store: EnhancedImageDiskCacheStore<ByteArray>,
    )

    private class ByteArrayCodec(
        private val failEncoding: Boolean = false,
    ) : EnhancedImageDiskCacheCodec<ByteArray> {
        override fun decode(file: File): ByteArray? =
            file.readBytes().takeUnless { it.contentEquals("corrupt".encodeToByteArray()) }

        override fun encode(value: ByteArray, output: OutputStream): Boolean {
            output.write(value)
            return !failEncoding
        }
    }

    private companion object {
        val ENTRY = EnhancedImageDiskCacheEntry(
            comicId = 42L,
            modelId = "model-2x",
            sourceRevision = "source-revision",
            cacheKey = "page-key",
        )
    }
}
