package com.exio.inkleaf.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

class OcrModelManifestTest {

    @Test
    fun `both model variants have independent directories and manifests`() {
        assertTrue(ocrModelDir(File("/data"), OcrModelVariant.SMALL).path.replace(File.separator, "/").endsWith("/ocr/ppocrv6_small"))
        assertTrue(ocrModelDir(File("/data"), OcrModelVariant.TINY).path.replace(File.separator, "/").endsWith("/ocr/ppocrv6_tiny"))
        assertTrue(OcrModelVariant.TINY.totalBytes < OcrModelVariant.SMALL.totalBytes)
        OcrModelVariant.values().forEach { variant ->
            assertEquals(variant.totalBytes, variant.files.sumOf { it.sizeBytes })
            assertTrue(variant.files.all { it.sha256.length == 64 })
        }
    }

    @Test
    fun `total bytes constant equals sum of spec sizes`() {
        val expected = OCR_MODEL_FILES.sumOf { it.sizeBytes }
        assertEquals(expected, OCR_MODEL_TOTAL_BYTES)
    }

    @Test
    fun `every spec carries repo and fileName so no when-dispatch is needed`() {
        // 回归保护：未来新增文件时若漏填 repo/fileName 应失败
        OCR_MODEL_FILES.forEach { spec ->
            assertTrue("repo blank for ${spec.relativePath}", spec.repo.isNotBlank())
            assertTrue("fileName blank for ${spec.relativePath}", spec.fileName.isNotBlank())
            assertTrue("sha256 blank for ${spec.relativePath}", spec.sha256.isNotBlank())
            assertTrue("sizeBytes non-positive for ${spec.relativePath}", spec.sizeBytes > 0L)
        }
    }

    @Test
    fun `relative paths are unique`() {
        val paths = OCR_MODEL_FILES.map { it.relativePath }
        assertEquals(paths.size, paths.toSet().size)
    }

    @Test
    fun `every source resolves to an https url for each spec`() {
        OCR_MODEL_SOURCES.forEach { source ->
            OCR_MODEL_FILES.forEach { spec ->
                val url = source.resolveUrl(spec.repo, spec.fileName)
                assertTrue("non-https from ${source.name}: $url", url.startsWith("https://"))
                assertTrue("url missing repo for ${source.name}: $url", url.contains(spec.repo))
                assertTrue("url missing fileName for ${source.name}: $url", url.contains(spec.fileName))
            }
        }
    }

    @Test
    fun `isOcrModelReady is false when version file missing`() {
        val dir = newTempDir()
        try {
            // 仅写入文件，不写 .version
            writeValidFiles(dir)
            assertFalse(isOcrModelReady(dir))
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `isOcrModelReady is false when version mismatch`() {
        val dir = newTempDir()
        try {
            writeValidFiles(dir)
            Files.writeString(modelDir(dir).resolve(".version"), "stale_version")
            assertFalse(isOcrModelReady(dir))
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `isOcrModelReady is false when a file is missing`() {
        val dir = newTempDir()
        try {
            writeValidFiles(dir)
            Files.delete(modelDir(dir).resolve(OCR_MODEL_FILES.first().relativePath))
            Files.writeString(modelDir(dir).resolve(".version"), OCR_MODEL_VERSION)
            assertFalse(isOcrModelReady(dir))
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `isOcrModelReady is false when a file has wrong size`() {
        val dir = newTempDir()
        try {
            writeValidFiles(dir)
            // 覆盖其中一个文件为短内容，大小不匹配
            val victim = modelDir(dir).resolve(OCR_MODEL_FILES.first().relativePath)
            Files.delete(victim)
            Files.write(victim, "wrong".toByteArray())
            Files.writeString(modelDir(dir).resolve(".version"), OCR_MODEL_VERSION)
            assertFalse(isOcrModelReady(dir))
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `isOcrModelReady is true when version matches and all files have correct size`() {
        val dir = newTempDir()
        try {
            writeValidFiles(dir)
            Files.writeString(modelDir(dir).resolve(".version"), OCR_MODEL_VERSION)
            assertTrue(isOcrModelReady(dir))
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `ocrModelDir is rooted under filesDir`() {
        val base = newTempDir()
        try {
            val resolved = ocrModelDir(base)
            assertEquals("ocr/ppocrv6_small", resolved.relativeTo(base).path.replace(File.separator, "/"))
        } finally {
            cleanup(base)
        }
    }

    // ---- helpers ----

    private fun newTempDir(): File =
        Files.createTempDirectory("ocr-manifest-test").toFile()

    private fun cleanup(dir: File) {
        dir.walkBottomUp().forEach { it.delete() }
        dir.toPath().deleteIfExists()
    }

    private fun modelDir(filesDir: File) = ocrModelDir(filesDir).toPath()

    /** 写出所有 spec 对应的占位文件，大小匹配 spec.sizeBytes（内容不参与 isOcrModelReady 校验）。 */
    private fun writeValidFiles(dir: File) {
        OCR_MODEL_FILES.forEach { spec ->
            val target = modelDir(dir).resolve(spec.relativePath)
            Files.createDirectories(target.parent)
            // 用稀疏文件填到指定大小，避免为 21MB 占位文件分配真实内存
            java.io.RandomAccessFile(target.toFile(), "rw").use { raf ->
                raf.setLength(spec.sizeBytes)
            }
        }
    }
}
