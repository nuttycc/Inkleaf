package com.exio.inkleaf.plugin

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRepoRepositoryCacheTest {
    private val indexJson =
        """
        {
          "schemaVersion": 1,
          "repoName": "Inkleaf 插件仓库",
          "plugins": [
            {
              "id": "io.inkleaf.fixture",
              "name": "Fixture",
              "version": "1.0.0",
              "pkgUrl": "https://example.com/plugins/fixture.zip"
            }
          ]
        }
        """
            .trimIndent()

    private fun clientServing(payload: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    chain
                        .proceed(chain.request())
                        .newBuilder()
                        .code(200)
                        .message("OK")
                        .protocol(Protocol.HTTP_1_1)
                        .body(payload.toByteArray().toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

    @Test
    fun `successful fetch persists cache and restores it`() {
        val directory = Files.createTempDirectory("inkleaf-plugin-repo").toFile()
        try {
            val repository =
                PluginRepoRepository(
                    clientServing(indexJson),
                    cacheFile = java.io.File(directory, "index-cache.json"),
                    clock = { 1_000L },
                )

            val fetched = runBlocking { repository.fetchIndex(PluginRepoContract.DEFAULT_REPO_URL) }
            val cached = runBlocking { repository.cachedIndex() }

            assertEquals(fetched.plugins.single().id, cached?.index?.plugins?.single()?.id)
            assertEquals(1_000L, cached?.fetchedAtMs)
            assertFalse(repository.isCacheExpired(cached!!))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cache older than the ttl counts as expired`() {
        val directory = Files.createTempDirectory("inkleaf-plugin-repo").toFile()
        try {
            var now = 1_000L
            val repository =
                PluginRepoRepository(
                    clientServing(indexJson),
                    cacheFile = java.io.File(directory, "index-cache.json"),
                    clock = { now },
                )
            runBlocking { repository.fetchIndex(PluginRepoContract.DEFAULT_REPO_URL) }
            val fresh = runBlocking { repository.cachedIndex() }!!

            now += PluginRepoContract.CACHE_TTL_MS - 1L
            assertFalse(repository.isCacheExpired(fresh))
            now += 1L
            assertTrue(repository.isCacheExpired(fresh))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt or missing cache reads as null`() {
        val directory = Files.createTempDirectory("inkleaf-plugin-repo").toFile()
        try {
            val cacheFile = java.io.File(directory, "index-cache.json")
            val repository =
                PluginRepoRepository(
                    clientServing(indexJson),
                    cacheFile = cacheFile,
                )

            assertNull(runBlocking { repository.cachedIndex() })

            cacheFile.writeText("definitely not a cache entry")
            assertNull(runBlocking { repository.cachedIndex() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed fetch keeps the previous cache intact`() {
        val directory = Files.createTempDirectory("inkleaf-plugin-repo").toFile()
        try {
            val servingClient = clientServing(indexJson)
            val repository =
                PluginRepoRepository(
                    servingClient,
                    cacheFile = java.io.File(directory, "index-cache.json"),
                    clock = { 1_000L },
                )
            runBlocking { repository.fetchIndex(PluginRepoContract.DEFAULT_REPO_URL) }

            val failingRepository =
                PluginRepoRepository(
                    OkHttpClient.Builder()
                        .addInterceptor(
                            Interceptor { chain ->
                                chain
                                    .proceed(chain.request())
                                    .newBuilder()
                                    .code(500)
                                    .message("Server Error")
                                    .protocol(Protocol.HTTP_1_1)
                                    .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                                    .build()
                            },
                        )
                        .build(),
                    cacheFile = java.io.File(directory, "index-cache.json"),
                )

            try {
                runBlocking { failingRepository.fetchIndex(PluginRepoContract.DEFAULT_REPO_URL) }
                org.junit.Assert.fail("expected IOException")
            } catch (_: java.io.IOException) {}

            val cached = runBlocking { repository.cachedIndex() }
            assertEquals("io.inkleaf.fixture", cached?.index?.plugins?.single()?.id)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cache round-trips through the same json shape`() {
        val entry = PluginRepoCacheEntry(PluginRepoIndex(1, "repo", "1.2", emptyList()), 42L)
        val json = Json { ignoreUnknownKeys = true }
        val serializer = PluginRepoCacheEntry.serializer()

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, entry))
        assertEquals(entry, decoded)
    }
}
