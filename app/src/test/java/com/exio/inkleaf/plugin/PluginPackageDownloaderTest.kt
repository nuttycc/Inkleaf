package com.exio.inkleaf.plugin

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginPackageDownloaderTest {
    @Test
    fun `cleartext plugin URL is rejected before download`() {
        val cache = Files.createTempDirectory("inkleaf-plugin-download").toFile()
        try {
            val downloader = PluginPackageDownloader(cacheDirectory = cache)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    downloader.download(PluginDownloadSource("http://example.com/plugin.zip"))
                }
            }
        } finally {
            cache.deleteRecursively()
        }
    }
}
