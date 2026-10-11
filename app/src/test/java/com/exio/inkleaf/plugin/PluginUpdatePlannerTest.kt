package com.exio.inkleaf.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUpdatePlannerTest {
    private fun entry(
        id: String = "io.inkleaf.source.demo",
        version: String = "1.0.0",
    ): PluginRepoEntry =
        PluginRepoEntry(
            id = id,
            name = "Demo",
            version = version,
            packageUrl = "https://example.com/$id-$version.zip",
        )

    private fun installed(
        id: String = "io.inkleaf.source.demo",
        activeVersion: String? = "1.0.0",
        versions: List<PluginVersionRecord> = activeVersion?.let { listOf(record(it)) }.orEmpty(),
    ): InstalledPlugin =
        InstalledPlugin(
            state =
                PluginState(
                    pluginId = id,
                    activeVersion = activeVersion,
                    versions = versions,
                ),
            directory = File("plugins/$id"),
            activeDirectory = activeVersion?.let { File("plugins/$id/versions/$it") },
        )

    private fun record(version: String, compatible: Boolean = true): PluginVersionRecord =
        PluginVersionRecord(
            version = version,
            sha256 = "0".repeat(64),
            compatible = compatible,
            installedAtMs = 0L,
        )

    @Test
    fun `no installed plugin yields not installed`() {
        assertEquals(PluginEntryState.NotInstalled, PluginUpdatePlanner.state(entry(), null))
    }

    @Test
    fun `newer repository version yields an update with both versions`() {
        val state =
            PluginUpdatePlanner.state(
                entry(version = "0.4.4"),
                installed(activeVersion = "0.4.3"),
            )

        assertEquals(
            PluginEntryState.UpdateAvailable("0.4.3", "0.4.4"),
            state,
        )
    }

    @Test
    fun `equal repository version is up to date`() {
        assertEquals(
            PluginEntryState.UpToDate,
            PluginUpdatePlanner.state(entry(version = "1.2.0"), installed(activeVersion = "1.2.0")),
        )
    }

    @Test
    fun `older repository version is up to date so no downgrade is offered`() {
        assertEquals(
            PluginEntryState.UpToDate,
            PluginUpdatePlanner.state(entry(version = "1.0.0"), installed(activeVersion = "1.1.0")),
        )
    }

    @Test
    fun `a version already on disk is up to date even when it is not active`() {
        val plugin =
            installed(
                activeVersion = "1.0.0",
                versions = listOf(record("1.0.0"), record("1.1.0", compatible = false)),
            )

        assertEquals(
            PluginEntryState.UpToDate,
            PluginUpdatePlanner.state(entry(version = "1.1.0"), plugin),
        )
    }

    @Test
    fun `an unparsable repository version yields unknown`() {
        assertEquals(
            PluginEntryState.Unknown,
            PluginUpdatePlanner.state(entry(version = "latest"), installed(activeVersion = "1.0.0")),
        )
    }

    @Test
    fun `an unparsable installed version yields unknown`() {
        assertEquals(
            PluginEntryState.Unknown,
            PluginUpdatePlanner.state(
                entry(version = "1.1.0"),
                installed(activeVersion = "nightly", versions = emptyList()),
            ),
        )
    }

    @Test
    fun `an installed plugin with nothing active yields unknown`() {
        assertEquals(
            PluginEntryState.Unknown,
            PluginUpdatePlanner.state(
                entry(version = "1.1.0"),
                installed(activeVersion = null, versions = emptyList()),
            ),
        )
    }

    @Test
    fun `a newer prerelease ranks above the stable version it supersedes`() {
        assertEquals(
            PluginEntryState.UpdateAvailable("1.0.0", "1.0.1-rc.1"),
            PluginUpdatePlanner.state(
                entry(version = "1.0.1-rc.1"),
                installed(activeVersion = "1.0.0"),
            ),
        )
        assertEquals(
            PluginEntryState.UpToDate,
            PluginUpdatePlanner.state(
                entry(version = "1.0.0-rc.1"),
                installed(activeVersion = "1.0.0"),
            ),
        )
    }

    @Test
    fun `updates selects only newer installed entries in index order`() {
        val entries =
            listOf(
                entry(id = "io.inkleaf.a", version = "2.0.0"),
                entry(id = "io.inkleaf.b", version = "1.0.0"),
                entry(id = "io.inkleaf.c", version = "1.0.0"),
            )
        val pluginList =
            listOf(
                installed(id = "io.inkleaf.a", activeVersion = "1.9.0"),
                installed(id = "io.inkleaf.b", activeVersion = "1.0.0"),
            )

        val candidates = PluginUpdatePlanner.updates(entries, pluginList)

        assertEquals(listOf("io.inkleaf.a"), candidates.map { it.entry.id })
        assertEquals("1.9.0", candidates.single().installedVersion)
        assertEquals("2.0.0", candidates.single().repositoryVersion)
    }

    @Test
    fun `updates is empty when nothing is installed`() {
        assertTrue(PluginUpdatePlanner.updates(listOf(entry()), emptyList()).isEmpty())
    }
}
