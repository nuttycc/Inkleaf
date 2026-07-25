package com.exio.inkleaf.plugin

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPackageValidatorTest {
    private val json = Json { encodeDefaults = true }
    private val validator = PluginPackageValidator()

    @Test
    fun `valid package is installable and activatable`() {
        val file = packageFile(validManifest())
        try {
            val result = validator.validate(file)
            assertTrue(result.installable)
            assertTrue(result.activatable)
            assertTrue(result.errors.isEmpty())
            assertEquals("'ok'", result.packageContent?.mainScript)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing required capability is retained but cannot activate`() {
        val file =
            packageFile(validManifest().copy(capabilities = listOf(PluginCapabilities.SEARCH)))
        try {
            val result = validator.validate(file)
            assertTrue(result.installable)
            assertFalse(result.activatable)
            assertTrue(
                result.incompatibilities.any {
                    it.code == PluginIssueCode.MISSING_REQUIRED_CAPABILITY
                }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `new API minor is retained but incompatible`() {
        val file = packageFile(validManifest().copy(apiVersion = "1.1"))
        try {
            val result = validator.validate(file)
            assertTrue(result.installable)
            assertFalse(result.activatable)
            assertTrue(
                result.incompatibilities.any { it.code == PluginIssueCode.API_MINOR_TOO_NEW }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `unknown optional capability only warns`() {
        val file = packageFile(validManifest().copy(capabilities = validManifest().capabilities + "futureFeature"))
        try {
            val result = validator.validate(file)
            assertTrue(result.installable)
            assertTrue(result.activatable)
            assertTrue(
                result.warnings.any {
                    it.code == PluginIssueCode.UNKNOWN_OPTIONAL_CAPABILITY
                }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `unknown manifest version rejects installation`() {
        val file = packageFile(validManifest().copy(manifestVersion = 2))
        try {
            val result = validator.validate(file)
            assertFalse(result.installable)
            assertTrue(
                result.errors.any {
                    it.code == PluginIssueCode.UNSUPPORTED_MANIFEST_VERSION
                }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `invalid id and version reject installation`() {
        val file =
            packageFile(
                validManifest().copy(
                    id = "Example.Source",
                    version = "1.0",
                )
            )
        try {
            val result = validator.validate(file)
            assertFalse(result.installable)
            assertTrue(result.errors.any { it.code == PluginIssueCode.INVALID_ID })
            assertTrue(result.errors.any { it.code == PluginIssueCode.INVALID_VERSION })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `path traversal and unexpected root entry reject package`() {
        val file = packageFile(validManifest(), extraEntries = mapOf("../escape.txt" to "x", "notes.txt" to "x"))
        try {
            val result = validator.validate(file)
            assertFalse(result.installable)
            assertTrue(result.errors.any { it.code == PluginIssueCode.UNSAFE_ENTRY_PATH })
            assertTrue(result.errors.any { it.code == PluginIssueCode.UNEXPECTED_ENTRY })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `semver follows prerelease ordering`() {
        assertTrue(requireNotNull(SemVer.parse("1.0.0-alpha")) < requireNotNull(SemVer.parse("1.0.0")))
        assertTrue(requireNotNull(SemVer.parse("1.0.0-alpha.1")) < requireNotNull(SemVer.parse("1.0.0-alpha.beta")))
        assertEquals(
            0,
            requireNotNull(SemVer.parse("1.2.3+build.1"))
                .compareTo(requireNotNull(SemVer.parse("1.2.3+build.2"))),
        )
    }

    @Test
    fun `api compatibility is major strict and minor forward compatible`() {
        assertEquals(
            PluginCompatibility.COMPATIBLE,
            requireNotNull(ApiVersion.parse("1.0")).compatibilityWith(ApiVersion(1, 0)),
        )
        assertEquals(
            PluginCompatibility.API_MINOR_TOO_NEW,
            requireNotNull(ApiVersion.parse("1.1")).compatibilityWith(ApiVersion(1, 0)),
        )
        assertEquals(
            PluginCompatibility.API_MAJOR_MISMATCH,
            requireNotNull(ApiVersion.parse("2.0")).compatibilityWith(ApiVersion(1, 0)),
        )
    }

    private fun validManifest() =
        PluginManifest(
            manifestVersion = 1,
            id = "io.example.source",
            name = "Example source",
            version = "1.0.0",
            apiVersion = "1.0",
            capabilities =
                listOf(
                    PluginCapabilities.SEARCH,
                    PluginCapabilities.DETAIL,
                    PluginCapabilities.CHAPTERS,
                    PluginCapabilities.PAGES,
                ),
            icon = "assets/icon.txt",
        )

    private fun packageFile(
        manifest: PluginManifest,
        extraEntries: Map<String, String> = emptyMap(),
    ): File {
        val file = Files.createTempFile("inkleaf-plugin", ".inkleaf-plugin").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            writeEntry(zip, PluginContract.MANIFEST_PATH, json.encodeToString(manifest))
            writeEntry(zip, PluginContract.ENTRY_PATH, "'ok'")
            writeEntry(zip, "assets/icon.txt", "icon")
            extraEntries.forEach { (name, value) -> writeEntry(zip, name, value) }
        }
        return file
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray())
        zip.closeEntry()
    }
}
