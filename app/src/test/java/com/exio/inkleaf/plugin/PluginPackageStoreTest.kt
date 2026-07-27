package com.exio.inkleaf.plugin

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPackageStoreTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `internal activation retains previous version for recovery`() = withStore { store, temp ->
        val first =
            packageFile(
                temp,
                manifest(version = "1.0.0"),
                "inkleaf.register({describe: async () => ({}), search: async () => ({}), detail: async () => ({}), chapters: async () => ({}), pages: async () => ({})})",
            )
        val second =
            packageFile(
                temp,
                manifest(version = "1.1.0"),
                "inkleaf.register({describe: async () => ({}), search: async () => ({}), detail: async () => ({}), chapters: async () => ({}), pages: async () => ({})})",
            )

        val installed = store.install(first)
        assertEquals(PluginInstallStatus.INSTALLED, installed.status)
        assertNull(store.get(PLUGIN_ID)?.state?.activeVersion)

        store.activate(PLUGIN_ID, "1.0.0")
        assertEquals("1.0.0", store.get(PLUGIN_ID)?.state?.activeVersion)

        store.install(second)
        store.activate(PLUGIN_ID, "1.1.0")
        val updated = requireNotNull(store.get(PLUGIN_ID))
        assertEquals("1.1.0", updated.state.activeVersion)
        assertEquals("1.0.0", updated.state.previousVersion)
        assertTrue(updated.directory.resolve("versions/1.0.0/main.js").isFile)
        assertTrue(updated.directory.resolve("versions/1.1.0/main.js").isFile)

        val restored = store.activate(PLUGIN_ID, requireNotNull(updated.state.previousVersion))
        assertEquals("1.0.0", restored.state.activeVersion)
        assertEquals("1.1.0", restored.state.previousVersion)
    }

    @Test
    fun `same version same digest is idempotent and different digest is rejected`() =
        withStore(clockMs = generateSequence(1L) { it + 1L }.iterator()::next) { store, temp ->
            val first = packageFile(temp, manifest(), "inkleaf.register({})")
            val same = first.copyTo(temp.resolve("same.zip"))
            val changed = packageFile(temp, manifest(), "inkleaf.register({changed: true})")

            assertEquals(
                PluginInstallStatus.INSTALLED,
                store.install(first, activate = true).status,
            )
            val updatedAtMs = store.get(PLUGIN_ID)?.state?.updatedAtMs
            assertEquals(
                PluginInstallStatus.ALREADY_INSTALLED,
                store.install(same, activate = true).status,
            )
            assertEquals("1.0.0", store.get(PLUGIN_ID)?.state?.activeVersion)
            assertEquals(updatedAtMs, store.get(PLUGIN_ID)?.state?.updatedAtMs)
            val conflict = store.install(changed)
            assertEquals(PluginInstallStatus.REJECTED, conflict.status)
            assertEquals(PluginInstallErrorCode.VERSION_CONFLICT, conflict.errorCode)
            assertEquals(1, store.get(PLUGIN_ID)?.state?.versions?.size)
        }

    @Test
    fun `package older than active version is rejected without storage changes`() =
        withStore { store, temp ->
            val active = packageFile(temp, manifest(version = "1.1.0"), "inkleaf.register({})")
            val older = packageFile(temp, manifest(version = "1.0.0"), "inkleaf.register({})")
            assertEquals(
                PluginInstallStatus.INSTALLED,
                store.install(active, activate = true).status,
            )

            val rejected = store.install(older, activate = true)

            assertEquals(PluginInstallStatus.REJECTED, rejected.status)
            assertEquals(PluginInstallErrorCode.DOWNGRADE_NOT_ALLOWED, rejected.errorCode)
            assertTrue(rejected.errorMessage?.contains("active version 1.1.0") == true)
            val installed = requireNotNull(store.get(PLUGIN_ID))
            assertEquals("1.1.0", installed.state.activeVersion)
            assertEquals(listOf("1.1.0"), installed.state.versions.map { it.version })
            assertFalse(installed.directory.resolve("versions/1.0.0").exists())
        }

    @Test
    fun `prerelease package is rejected below an active release`() = withStore { store, temp ->
        val release = packageFile(temp, manifest(version = "1.0.0"), "inkleaf.register({})")
        val prerelease = packageFile(temp, manifest(version = "1.0.0-rc.1"), "inkleaf.register({})")
        store.install(release, activate = true)

        val rejected = store.install(prerelease)

        assertEquals(PluginInstallStatus.REJECTED, rejected.status)
        assertEquals(PluginInstallErrorCode.DOWNGRADE_NOT_ALLOWED, rejected.errorCode)
    }

    @Test
    fun `update is compared with active version instead of highest retained version`() =
        withStore { store, temp ->
            val active = packageFile(temp, manifest(version = "1.0.0"), "inkleaf.register({})")
            val incompatible =
                packageFile(
                    temp,
                    manifest(version = "3.0.0", apiVersion = "2.0"),
                    "inkleaf.register({})",
                )
            val update = packageFile(temp, manifest(version = "2.0.0"), "inkleaf.register({})")
            store.install(active, activate = true)
            store.install(incompatible)

            val installed = store.install(update, activate = true)

            assertEquals(PluginInstallStatus.INSTALLED, installed.status)
            val state = requireNotNull(store.get(PLUGIN_ID)).state
            assertEquals("2.0.0", state.activeVersion)
            assertEquals(
                setOf("1.0.0", "2.0.0", "3.0.0"),
                state.versions.map { it.version }.toSet(),
            )
        }

    @Test
    fun `incompatible version is retained without replacing active version`() =
        withStore { store, temp ->
            val active = packageFile(temp, manifest(version = "1.0.0"), "inkleaf.register({})")
            val incompatible =
                packageFile(
                    temp,
                    manifest(version = "2.0.0", apiVersion = "2.0"),
                    "inkleaf.register({})",
                )
            store.install(active)
            store.activate(PLUGIN_ID, "1.0.0")

            val result = store.install(incompatible, activate = true)
            assertEquals(PluginInstallStatus.INSTALLED, result.status)
            assertFalse(result.activatable)
            val state = requireNotNull(store.get(PLUGIN_ID)).state
            assertEquals("1.0.0", state.activeVersion)
            assertTrue(state.versions.any { it.version == "2.0.0" && !it.compatible })
        }

    @Test
    fun `explicit assets directory entry is accepted`() = withStore { store, temp ->
        val file =
            packageFile(temp, manifest(), "inkleaf.register({})", explicitAssetsDirectory = true)
        assertEquals(PluginInstallStatus.INSTALLED, store.install(file).status)
    }

    @Test
    fun `three fatal failures inside window require explicit recovery`() =
        withStore { store, temp ->
            store.install(packageFile(temp, manifest(), "inkleaf.register({})"))
            store.activate(PLUGIN_ID, "1.0.0")
            store.recordFatalFailure(PLUGIN_ID, 1_000L)
            store.recordFatalFailure(PLUGIN_ID, 2_000L)
            assertEquals(PluginHealth.HEALTHY, store.get(PLUGIN_ID)?.state?.health)
            store.recordFatalFailure(PLUGIN_ID, 3_000L)
            assertEquals(PluginHealth.RUNTIME_UNHEALTHY, store.get(PLUGIN_ID)?.state?.health)

            store.clearHealth(PLUGIN_ID)
            assertEquals(PluginHealth.HEALTHY, store.get(PLUGIN_ID)?.state?.health)
            assertTrue(store.get(PLUGIN_ID)?.state?.fatalFailureTimesMs?.isEmpty() == true)
        }

    private fun withStore(
        clockMs: () -> Long = { 10_000L },
        block: (PluginPackageStore, File) -> Unit,
    ) {
        val temp = Files.createTempDirectory("inkleaf-plugin-store").toFile()
        try {
            block(PluginPackageStore(temp.resolve("plugins"), clockMs = clockMs), temp)
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun manifest(
        version: String = "1.0.0",
        apiVersion: String = "1.0",
    ) =
        PluginManifest(
            manifestVersion = 1,
            id = PLUGIN_ID,
            name = "Example",
            version = version,
            apiVersion = apiVersion,
            capabilities = PluginCapabilities.required.sorted(),
            icon = "assets/icon.txt",
        )

    private fun packageFile(
        directory: File,
        manifest: PluginManifest,
        mainScript: String,
        explicitAssetsDirectory: Boolean = false,
    ): File {
        val file = directory.resolve("${manifest.version}-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            write(zip, PluginContract.MANIFEST_PATH, json.encodeToString(manifest))
            write(zip, PluginContract.ENTRY_PATH, mainScript)
            if (explicitAssetsDirectory) {
                zip.putNextEntry(ZipEntry("assets/"))
                zip.closeEntry()
            }
            write(zip, "assets/icon.txt", "icon")
        }
        return file
    }

    private fun write(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray())
        zip.closeEntry()
    }

    private companion object {
        const val PLUGIN_ID = "io.example.source"
    }
}
