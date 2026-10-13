package com.exio.inkleaf.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateManifestParserTest {
    @Test
    fun `parses a well-formed manifest`() {
        val text =
            """
            {
              "schemaVersion": 2,
              "release": {
                "version": "2026.1013.1345",
                "versionCode": 1000725,
                "apkUrl": "https://github.com/nuttycc/inkleaf/releases/download/v2026.1013.1345/Inkleaf-2026.1013.1345.apk",
                "releaseNotes": "修复了已知问题",
                "publishedAt": "2026-10-13T05:45:00Z",
                "sha256": "abc123",
                "sizeBytes": 12345678
              },
              "unknownField": "ignored"
            }
            """
                .trimIndent()

        val manifest = AppUpdateManifestParser.parse(text)

        assertEquals(2, manifest.schemaVersion)
        val release = manifest.release
        assertNotNull(release)
        assertEquals("2026.1013.1345", release!!.version)
        assertEquals(1000725L, release.versionCode)
        assertEquals(
            "https://github.com/nuttycc/inkleaf/releases/download/v2026.1013.1345/Inkleaf-2026.1013.1345.apk",
            release.apkUrl,
        )
        assertEquals("修复了已知问题", release.releaseNotes)
        assertEquals("abc123", release.sha256)
        assertEquals(12345678L, release.sizeBytes)
    }

    @Test
    fun `allows a null release entry`() {
        val manifest = AppUpdateManifestParser.parse("""{"schemaVersion": 2}""")

        assertNull(manifest.release)
    }

    @Test
    fun `rejects unsupported schema versions`() {
        try {
            AppUpdateManifestParser.parse("""{"schemaVersion": 1, "release": null}""")
            fail("expected AppUpdateManifestException")
        } catch (error: AppUpdateManifestException) {
            assertEquals("更新清单版本不受支持: 1", error.message)
        }
    }

    @Test
    fun `rejects release entry with blank version`() {
        val text =
            """
            {
              "schemaVersion": 2,
              "release": {
                "version": "",
                "versionCode": 100,
                "apkUrl": "https://example.com/app.apk"
              }
            }
            """
                .trimIndent()

        try {
            AppUpdateManifestParser.parse(text)
            fail("expected AppUpdateManifestException")
        } catch (error: AppUpdateManifestException) {
            assertEquals("更新清单 release 通道缺少必要字段", error.message)
        }
    }

    @Test
    fun `rejects release entry with non-https apk url`() {
        val text =
            """
            {
              "schemaVersion": 2,
              "release": {
                "version": "2026.1013.1345",
                "versionCode": 100,
                "apkUrl": "http://example.com/app.apk"
              }
            }
            """
                .trimIndent()

        try {
            AppUpdateManifestParser.parse(text)
            fail("expected AppUpdateManifestException")
        } catch (error: AppUpdateManifestException) {
            assertEquals("更新清单 release 通道缺少必要字段", error.message)
        }
    }

    @Test
    fun `rejects release entry with zero version code`() {
        val text =
            """
            {
              "schemaVersion": 2,
              "release": {
                "version": "2026.1013.1345",
                "versionCode": 0,
                "apkUrl": "https://example.com/app.apk"
              }
            }
            """
                .trimIndent()

        try {
            AppUpdateManifestParser.parse(text)
            fail("expected AppUpdateManifestException")
        } catch (error: AppUpdateManifestException) {
            assertEquals("更新清单 release 通道缺少必要字段", error.message)
        }
    }

    @Test
    fun `rejects malformed json`() {
        try {
            AppUpdateManifestParser.parse("not json at all")
            fail("expected AppUpdateManifestException")
        } catch (error: AppUpdateManifestException) {
            assertEquals("更新清单格式无效", error.message)
        }
    }
}
