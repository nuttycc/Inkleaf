package com.exio.inkleaf.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PluginRepoIndexParserTest {
    @Test
    fun `parses a well-formed index with unknown fields`() {
        val text =
            """
            {
              "schemaVersion": 1,
              "repoName": "Inkleaf 插件仓库",
              "apiVersion": "1.2",
              "futureField": "ignored",
              "plugins": [
                {
                  "id": "io.inkleaf.source.copycomic",
                  "name": "拷贝漫画",
                  "version": "0.4.3",
                  "apiVersion": "1.1",
                  "capabilities": ["search", "pages"],
                  "description": "desc",
                  "author": "Inkleaf",
                  "pkgUrl": "https://example.com/plugins/copycomic-0.4.3.zip",
                  "sha256": "abc123",
                  "sizeBytes": 7670,
                  "unknownEntryField": 1
                }
              ]
            }
            """
                .trimIndent()

        val index = PluginRepoIndexParser.parse(text)

        assertEquals(1, index.schemaVersion)
        assertEquals("Inkleaf 插件仓库", index.repoName)
        assertEquals("1.2", index.apiVersion)
        assertEquals(1, index.plugins.size)
        val entry = index.plugins.single()
        assertEquals("io.inkleaf.source.copycomic", entry.id)
        assertEquals("拷贝漫画", entry.name)
        assertEquals("0.4.3", entry.version)
        assertEquals("1.1", entry.apiVersion)
        assertEquals("https://example.com/plugins/copycomic-0.4.3.zip", entry.packageUrl)
        assertEquals("abc123", entry.sha256)
        assertEquals(7670L, entry.sizeBytes)
        assertNull(entry.iconUrl)
    }

    @Test
    fun `rejects unsupported schema versions`() {
        val text = """{"schemaVersion": 2, "plugins": []}"""

        try {
            PluginRepoIndexParser.parse(text)
            fail("expected PluginRepoIndexFormatException")
        } catch (error: PluginRepoIndexFormatException) {
            assertEquals("插件仓库索引版本不受支持: 2", error.message)
        }
    }

    @Test
    fun `rejects index without plugins`() {
        val text = """{"schemaVersion": 1, "plugins": []}"""

        try {
            PluginRepoIndexParser.parse(text)
            fail("expected PluginRepoIndexFormatException")
        } catch (error: PluginRepoIndexFormatException) {
            assertEquals("插件仓库索引不包含任何插件", error.message)
        }
    }

    @Test
    fun `rejects entries with blank or non-https package urls`() {
        val missingName =
            """
            {
              "schemaVersion": 1,
              "plugins": [
                {
                  "id": "io.inkleaf.source.a",
                  "name": "",
                  "version": "1.0.0",
                  "pkgUrl": "https://example.com/a.zip"
                }
              ]
            }
            """
                .trimIndent()
        val httpPackageUrl =
            """
            {
              "schemaVersion": 1,
              "plugins": [
                {
                  "id": "io.inkleaf.source.a",
                  "name": "A",
                  "version": "1.0.0",
                  "pkgUrl": "http://example.com/a.zip"
                }
              ]
            }
            """
                .trimIndent()

        listOf(missingName, httpPackageUrl).forEach { text ->
            try {
                PluginRepoIndexParser.parse(text)
                fail("expected PluginRepoIndexFormatException")
            } catch (error: PluginRepoIndexFormatException) {
                assertEquals("插件条目缺少必要字段: io.inkleaf.source.a", error.message)
            }
        }
    }

    @Test
    fun `rejects malformed json`() {
        try {
            PluginRepoIndexParser.parse("not json at all")
            fail("expected PluginRepoIndexFormatException")
        } catch (error: PluginRepoIndexFormatException) {
            assertEquals("插件仓库索引格式无效", error.message)
        }
    }
}
