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
    fun `install activation update and rollback keep immutable versions`() =
        withStore { store, temp ->
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

            val rolledBack = requireNotNull(store.rollback(PLUGIN_ID))
            assertEquals("1.0.0", rolledBack.state.activeVersion)
            assertEquals("1.1.0", rolledBack.state.previousVersion)
        }

    @Test
    fun `same version same digest is idempotent and different digest is rejected`() =
        withStore { store, temp ->
            val first = packageFile(temp, manifest(), "inkleaf.register({})")
            val same = first.copyTo(temp.resolve("same.zip"))
            val changed = packageFile(temp, manifest(), "inkleaf.register({changed: true})")

            assertEquals(PluginInstallStatus.INSTALLED, store.install(first).status)
            assertEquals(PluginInstallStatus.ALREADY_INSTALLED, store.install(same).status)
            val conflict = store.install(changed)
            assertEquals(PluginInstallStatus.REJECTED, conflict.status)
            assertEquals(PluginInstallErrorCode.VERSION_CONFLICT, conflict.errorCode)
            assertEquals(1, store.get(PLUGIN_ID)?.state?.versions?.size)
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

    private fun withStore(block: (PluginPackageStore, File) -> Unit) {
        val temp = Files.createTempDirectory("inkleaf-plugin-store").toFile()
        try {
            block(PluginPackageStore(temp.resolve("plugins"), clockMs = { 10_000L }), temp)
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
