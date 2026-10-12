package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginInstallErrorCode
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginInstallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInstallReportingTest {

    /**
     * Checks successful install wording, informational severity and the available open-source
     * action.
     */
    @Test
    fun `install verb reads a fresh install as installed`() {
        val message =
            sourceInstallMessage(
                outcome = installed(activatable = true),
                verb = SourceInstallVerb.Install,
                fallbackName = "未知插件",
            )

        assertEquals("已安装 demo v0.2.0", message.text)
        assertFalse(message.isError)
        assertEquals("demo", message.openPluginId)
    }

    /** Checks that the update verb changes success wording without changing severity. */
    @Test
    fun `update verb reads the same outcome as updated`() {
        val message =
            sourceInstallMessage(
                outcome = installed(activatable = true),
                verb = SourceInstallVerb.Update,
                fallbackName = "未知插件",
            )

        assertEquals("已更新 demo v0.2.0", message.text)
        assertFalse(message.isError)
    }

    /** Uses the caller-provided display name when an outcome lacks a plugin ID. */
    @Test
    fun `a missing plugin id falls back to the caller's name`() {
        val message =
            sourceInstallMessage(
                outcome = installed(activatable = true, pluginId = null),
                verb = SourceInstallVerb.Update,
                fallbackName = "绅士漫画",
            )

        assertEquals("已更新 绅士漫画 v0.2.0", message.text)
    }

    /** Omits the version label when the install outcome has no version. */
    @Test
    fun `a version-less outcome omits the version suffix`() {
        val message =
            sourceInstallMessage(
                outcome = installed(activatable = true, version = null),
                verb = SourceInstallVerb.Install,
                fallbackName = "未知插件",
            )

        assertEquals("已安装 demo", message.text)
    }

    /**
     * Keeps installed wording and navigation for an incompatible source while marking the outcome
     * as an error.
     */
    @Test
    fun `an unactivatable install still reads as installed but flags the error`() {
        val message =
            sourceInstallMessage(
                outcome = installed(activatable = false),
                verb = SourceInstallVerb.Update,
                fallbackName = "未知插件",
            )

        assertEquals("已安装 demo v0.2.0，但未激活：与当前版本不兼容", message.text)
        assertTrue(message.isError)
        assertEquals("demo", message.openPluginId)
    }

    /** Checks that rejection uses failure wording and cannot offer navigation to a source. */
    @Test
    fun `a rejection reads with the verb's failure text and offers nothing to open`() {
        val message =
            sourceInstallMessage(
                outcome =
                    PluginInstallResult(
                        status = PluginInstallStatus.REJECTED,
                        pluginId = "demo",
                        errorMessage = "版本不兼容",
                    ),
                verb = SourceInstallVerb.Update,
                fallbackName = "未知插件",
            )

        assertEquals("更新失败: 版本不兼容", message.text)
        assertTrue(message.isError)
        assertNull(message.openPluginId)
    }

    /** Uses the error-code name when a rejected outcome has no human-readable explanation. */
    @Test
    fun `a rejection without a message falls back to the error code name`() {
        val message =
            sourceInstallMessage(
                outcome =
                    PluginInstallResult(
                        status = PluginInstallStatus.REJECTED,
                        errorCode = PluginInstallErrorCode.VERSION_CONFLICT,
                    ),
                verb = SourceInstallVerb.Install,
                fallbackName = "未知插件",
            )

        assertEquals("安装失败: VERSION_CONFLICT", message.text)
    }

    /** Checks that the inline success card names the source and version without an operation verb. */
    @Test
    fun `the in-page card reads a successful install without a verb`() {
        assertEquals(
            "已安装 demo v0.2.0",
            installCardMessage(installed(activatable = true), fallbackName = "未知插件"),
        )
    }

    /** Explains activation incompatibility in the inline card while preserving installed status. */
    @Test
    fun `the in-page card explains an unactivatable install`() {
        assertEquals(
            "已安装 demo v0.2.0，但未激活：与当前版本不兼容",
            installCardMessage(installed(activatable = false), fallbackName = "未知插件"),
        )
    }

    /** Counts already-installed outcomes as updates and only rejected outcomes as failures. */
    @Test
    fun `an update sweep counts only rejections as failures`() {
        val summary =
            summarizeUpdateAll(
                listOf(
                    installed(activatable = true),
                    PluginInstallResult(status = PluginInstallStatus.ALREADY_INSTALLED),
                    PluginInstallResult(status = PluginInstallStatus.REJECTED),
                )
            )

        assertEquals(2, summary.updated)
        assertEquals(1, summary.failed)
        assertEquals("已更新 2 个漫画源，1 个失败", summary.text)
        assertTrue(summary.isError)
    }

    /** Checks that a sweep with no rejections uses success wording and informational severity. */
    @Test
    fun `a clean update sweep reports no failures`() {
        val summary = summarizeUpdateAll(listOf(installed(activatable = true)))

        assertEquals("已更新 1 个漫画源", summary.text)
        assertFalse(summary.isError)
    }

    /** Creates an installed outcome with configurable activation, identity and version metadata. */
    private fun installed(
        activatable: Boolean,
        pluginId: String? = "demo",
        version: String? = "0.2.0",
    ) = PluginInstallResult(
        status = PluginInstallStatus.INSTALLED,
        pluginId = pluginId,
        version = version,
        activatable = activatable,
    )
}
