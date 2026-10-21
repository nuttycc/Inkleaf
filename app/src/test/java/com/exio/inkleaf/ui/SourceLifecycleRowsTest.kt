package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.InstalledPlugin
import com.exio.inkleaf.plugin.PluginHealth
import com.exio.inkleaf.plugin.PluginRepoEntry
import com.exio.inkleaf.plugin.PluginRepoIndex
import com.exio.inkleaf.plugin.PluginState
import com.exio.inkleaf.plugin.PluginVersionRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceLifecycleRowsTest {

    @Test
    fun `the installed list keeps device-only sources and drops uninstalled catalog entries`() {
        val rows =
            SourceLifecycleRows.installedRows(
                index = indexOf(entry("b_repo", "B 源", "1.0.0")),
                installed = listOf(installed("a_local", activeVersion = "2.0.0")),
            )

        assertEquals(listOf("a_local"), rows.map { it.pluginId })
        assertEquals("本地导入 · v2.0.0", rows.single().statusText)
        assertEquals(SourceRowAction.Manage, rows.single().action)
    }

    @Test
    fun `the catalog list follows repository order and marks what is already installed`() {
        val rows =
            SourceLifecycleRows.catalogRows(
                indexOf(entry("first", "第一源", "1.0.0"), entry("second", "第二源", "1.0.0")),
                installed = listOf(installed("second", activeVersion = "1.0.0", versions = "1.0.0")),
            )

        assertEquals(listOf("first", "second"), rows.map { it.pluginId })
        assertEquals(SourceRowAction.Install, rows[0].action)
        assertEquals("未安装 · v1.0.0", rows[0].statusText)
        assertEquals("广告描述", rows[0].description)
        assertEquals(SourceRowAction.Manage, rows[1].action)
        assertEquals("已安装 · v1.0.0", rows[1].statusText)
    }

    @Test
    fun `a strictly newer repository version offers update and states both versions`() {
        val installedOnly =
            SourceLifecycleRows.installedRows(
                indexOf(entry("jm", "禁漫天堂", "0.1.3")),
                listOf(installed("jm", activeVersion = "0.1.1", versions = "0.1.1")),
            )
        val catalog =
            SourceLifecycleRows.catalogRows(
                indexOf(entry("jm", "禁漫天堂", "0.1.3")),
                listOf(installed("jm", activeVersion = "0.1.1", versions = "0.1.1")),
            )

        assertEquals(SourceRowAction.Update, installedOnly.single().action)
        assertEquals(SourceRowAction.Update, catalog.single().action)
        assertEquals("有更新 · 当前 v0.1.1，仓库 v0.1.3", catalog.single().statusText)
    }

    @Test
    fun `a plugin already at the repository version is managed, never offered an update`() {
        val rows =
            SourceLifecycleRows.installedRows(
                indexOf(entry("jm", "禁漫天堂", "0.1.3")),
                listOf(installed("jm", activeVersion = "0.1.3", versions = "0.1.3")),
            )

        assertEquals(SourceRowAction.Manage, rows.single().action)
        assertEquals("已安装 · v0.1.3", rows.single().statusText)
    }

    @Test
    fun `an installed but unactivatable version states both facts instead of hiding one`() {
        val rows =
            SourceLifecycleRows.installedRows(
                indexOf(entry("jm", "禁漫天堂", "0.1.3")),
                listOf(installed("jm", activeVersion = null, versions = "0.1.3")),
            )

        assertEquals(SourceRowAction.Manage, rows.single().action)
        assertEquals("已安装，未激活 · 仓库 v0.1.3", rows.single().statusText)
    }

    @Test
    fun `unhealthy sources state their condition`() {
        val broken =
            installed("jm", activeVersion = "0.1.3", versions = "0.1.3").let {
                it.copy(state = it.state.copy(health = PluginHealth.RUNTIME_UNHEALTHY))
            }

        val rows = SourceLifecycleRows.installedRows(indexOf(entry("jm", "禁漫天堂", "0.1.3")), listOf(broken))

        assertEquals("已安装 · v0.1.3 · 运行异常", rows.single().statusText)
    }

    @Test
    fun `usableCount excludes unhealthy and never-activated sources`() {
        val healthy = installed("a", activeVersion = "1.0.0", versions = "1.0.0")
        val unhealthy = healthy.copy(state = healthy.state.copy(health = PluginHealth.RUNTIME_UNHEALTHY))
        val inactive = installed("c", activeVersion = null, versions = "1.0.0")

        assertEquals(1, SourceLifecycleRows.usableCount(listOf(healthy, unhealthy, inactive)))
    }

    @Test
    fun `an unloaded index still lists what is on the device`() {
        val rows =
            SourceLifecycleRows.installedRows(null, listOf(installed("a_local", activeVersion = "1.0.0")))

        assertEquals(listOf("a_local"), rows.map { it.pluginId })
        assertEquals("本地导入 · v1.0.0", rows.single().statusText)
        assertEquals(emptyList<SourceLifecycleRow>(), SourceLifecycleRows.catalogRows(null, emptyList()))
    }

    private fun indexOf(vararg entries: PluginRepoEntry) =
        PluginRepoIndex(schemaVersion = 1, repoName = "测试仓库", plugins = entries.toList())

    private fun entry(id: String, name: String, version: String) =
        PluginRepoEntry(
            id = id,
            name = name,
            version = version,
            description = "广告描述",
            packageUrl = "https://example.invalid/$id.zip",
        )

    private fun installed(
        pluginId: String,
        activeVersion: String?,
        versions: String? = activeVersion,
    ): InstalledPlugin =
        InstalledPlugin(
            state =
                PluginState(
                    pluginId = pluginId,
                    activeVersion = activeVersion,
                    versions =
                        versions
                            ?.let { listOf(PluginVersionRecord(it, "sha", compatible = true, 0L)) }
                            ?: emptyList(),
                ),
            directory = File(pluginId),
            activeDirectory = null,
        )
}
