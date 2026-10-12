package com.exio.inkleaf.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateManifestParserTest {
    @Test
    fun `parses a well-formed manifest with both channels`() {
        val text =
            """
            {
              "schemaVersion": 1,
              "stable": {
                "version": "0.1.113",
                "versionCode": 1000227,
                "apkUrl": "https://github.com/nuttycc/inkleaf/releases/download/v0.1.113/Inkleaf-v0.1.113-release.apk",
                "releaseNotes": "修复了已知问题",
                "publishedAt": "2026-10-11T03:57:14Z",
                "sha256": "abc123",
                "sizeBytes": 12345678
              },
              "preview": {
                "version": "0.1.113-preview.20261012.86",
                "versionCode": 1000228,
                "apkUrl": "https://github.com/nuttycc/inkleaf/releases/download/preview-20261012/Inkleaf-preview.apk",
                "releaseNotes": "今日预览",
                "publishedAt": "2026-10-11T23:42:58Z"
              },
              "unknownField": "ignored"
            }
            """
                .trimIndent()

        val manifest = AppUpdateManifestParser.parse(text)

        assertEquals(1, manifest.schemaVersion)
        assertNotNull(manifest.stable)
        assertNotNull(manifest.preview)

        val stable = manifest.stable!!
        assertEquals("0.1.113", stable.version)
        assertEquals(1000227L, stable.versionCode)
        assertEquals(
            "https://github.com/nuttycc/inkleaf/releases/download/v0.1.113/Inkleaf-v0.1.113-release.apk",
            stable.apkUrl,
        )
        assertEquals("修复了已知问题", stable.releaseNotes)
        assertEquals("abc123", stable.sha256)
        assertEquals(12345678L, stable.sizeBytes)

        val preview = manifest.preview!!
        assertEquals("0.1.113-preview.20261012.86", preview.version)
        assertEquals(1000228L, preview.versionCode)
        assertNull(preview.sha256)
    }

    @Test
    fun `parses manifest with only stable channel`() {
        val text =
            """
            {
              "schemaVersion": 1,
              "stable": {
                "version": "1.0.0",
                "versionCode": 100,
                "apkUrl": "https://example.com/app.apk"
              }
            }
            """
                .trimIndent()

        val manifest = AppUpdateManifestParser.parse(text)

        assertNotNull(manifest.stable)
        assertNull(manifest.preview)
        assertEquals("1.0.0", manifest.stable!!.version)
    }

    @Test
    fun `rejects unsupported schema versions`() {
        val text = """{"schemaVersion": 2, "stable": null}"""

        try {
            AppUpdateManifestParser.parse(text)
            fail("expected AppUpdateManifestException")
        } catch (error: AppUpdateManifestException) {
            assertEquals("更新清单版本不受支持: 2", error.message)
        }
    }

    @Test
    fun `rejects stable entry with blank version`() {
        val text =
            """
            {
              "schemaVersion": 1,
              "stable": {
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
            assertEquals("更新清单 stable 通道缺少必要字段", error.message)
        }
    }

    @Test
    fun `rejects entry with non-https apk url`() {
        val text =
            """
            {
              "schemaVersion": 1,
              "stable": {
                "version": "1.0.0",
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
            assertEquals("更新清单 stable 通道缺少必要字段", error.message)
        }
    }

    @Test
    fun `rejects entry with zero version code`() {
        val text =
            """
            {
              "schemaVersion": 1,
              "stable": {
                "version": "1.0.0",
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
            assertEquals("更新清单 stable 通道缺少必要字段", error.message)
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

    @Test
    fun `allows both channels to be null`() {
        val text = """{"schemaVersion": 1}"""

        val manifest = AppUpdateManifestParser.parse(text)

        assertNull(manifest.stable)
        assertNull(manifest.preview)
    }
}
