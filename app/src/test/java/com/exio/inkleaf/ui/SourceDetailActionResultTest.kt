package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginSessionState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceDetailActionResultTest {
    @Test
    fun `object message falls back without interrupting action completion`() {
        val result = buildJsonObject { put("message", buildJsonObject { put("text", "done") }) }

        assertEquals("刷新：操作完成", sourceActionMessage(result, "刷新"))
    }

    @Test
    fun `string message is shown`() {
        val result = buildJsonObject { put("message", "done") }

        assertEquals("done", sourceActionMessage(result, "刷新"))
    }

    @Test
    fun `login records the reported account`() {
        val result = buildJsonObject { put("account", "alice") }

        val next =
            sessionStateAfterAction(
                PluginSessionState(),
                PluginActionDescriptor("login", "Login", kind = "login"),
                result,
            )

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), next)
    }

    @Test
    fun `login keeps the previous account when the plugin reports none`() {
        val previous = PluginSessionState(signedIn = true, account = "alice")

        val next =
            sessionStateAfterAction(
                previous,
                PluginActionDescriptor("login", "Login", kind = "login"),
                buildJsonObject { put("message", "登录成功") },
            )

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), next)
    }

    @Test
    fun `verifying credentials also marks the source signed in`() {
        val next =
            sessionStateAfterAction(
                PluginSessionState(),
                PluginActionDescriptor("verify", "Verify", kind = "verifyCredentials"),
                buildJsonObject { put("account", "alice") },
            )

        assertEquals(PluginSessionState(signedIn = true, account = "alice"), next)
    }

    @Test
    fun `sign out clears the session`() {
        val previous = PluginSessionState(signedIn = true, account = "alice")

        val next =
            sessionStateAfterAction(
                previous,
                PluginActionDescriptor("logout", "Logout", kind = "clearSession"),
                buildJsonObject { put("message", "已退出登录") },
            )

        assertEquals(PluginSessionState(), next)
    }

    @Test
    fun `unrelated actions leave the session untouched`() {
        val previous = PluginSessionState(signedIn = true, account = "alice")

        val next =
            sessionStateAfterAction(
                previous,
                PluginActionDescriptor("refresh", "Refresh"),
                buildJsonObject { put("message", "done") },
            )

        assertEquals(previous, next)
    }
}
