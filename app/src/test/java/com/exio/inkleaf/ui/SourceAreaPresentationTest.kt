package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginSessionState
import com.exio.inkleaf.plugin.PluginSettingDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAreaPresentationTest {

    private fun setting(id: String, section: String? = null, type: String = "text") =
        PluginSettingDescriptor(id = id, title = id, section = section, type = type)

    private fun action(id: String, kind: String = "action", section: String? = null) =
        PluginActionDescriptor(id = id, title = id, kind = kind, section = section)

    private fun presentation(
        settings: List<PluginSettingDescriptor>,
        actions: List<PluginActionDescriptor>,
        signedIn: Boolean = false,
    ) =
        sourceAreaPresentation(
            sourceAreas(settings, actions).single(),
            PluginSessionState(signedIn = signedIn),
        )

    @Test
    fun `a named area keeps its settings in the card and actions on the heading`() {
        val presentation =
            presentation(
                settings = listOf(setting("route", "线路"), setting("auto", "线路", "boolean")),
                actions = listOf(action("refresh", section = "线路")),
            )

        assertTrue(presentation.named)
        assertEquals("线路", presentation.title)
        assertFalse(presentation.showsStatus)
        assertEquals(listOf("refresh"), presentation.headingActions.map { it.id })
        assertNull(presentation.settingsHeader)
        assertTrue(presentation.showsSettingsCard)
        assertEquals(listOf("route", "auto"), presentation.cardSettings.map { it.id })
        assertFalse(presentation.showsAccountRow)
        assertNull(presentation.actionsHeader)
        assertTrue(presentation.listActions.isEmpty())
    }

    @Test
    fun `a named action-only area still has its heading`() {
        val presentation =
            presentation(
                settings = emptyList(),
                actions = listOf(action("refresh", section = "线路")),
            )

        assertTrue(presentation.named)
        assertFalse(presentation.showsSettingsCard)
        assertTrue(presentation.cardSettings.isEmpty())
        assertEquals(listOf("refresh"), presentation.headingActions.map { it.id })
    }

    @Test
    fun `a generic area separates its settings and action list`() {
        val presentation =
            presentation(
                settings = listOf(setting("showAdult")),
                actions = listOf(action("refresh")),
            )

        assertFalse(presentation.named)
        assertEquals("操作", presentation.title)
        assertEquals("设置", presentation.settingsHeader)
        assertEquals("操作", presentation.actionsHeader)
        assertEquals(listOf("refresh"), presentation.listActions.map { it.id })
        assertTrue(presentation.headingActions.isEmpty())
        assertTrue(presentation.showsSettingsCard)
        assertEquals(listOf("showAdult"), presentation.cardSettings.map { it.id })
    }

    @Test
    fun `a generic action-only area has no settings header or card`() {
        val presentation =
            presentation(settings = emptyList(), actions = listOf(action("refresh")))

        assertFalse(presentation.named)
        assertNull(presentation.settingsHeader)
        assertFalse(presentation.showsSettingsCard)
        assertEquals("操作", presentation.actionsHeader)
        assertEquals(listOf("refresh"), presentation.listActions.map { it.id })
    }

    @Test
    fun `a named account area keeps credentials in the sheet and the account row in the card`() {
        val presentation =
            presentation(
                settings =
                    listOf(
                        setting("account", "登录"),
                        setting("password", "登录", type = "secret"),
                    ),
                actions =
                    listOf(
                        action("login", kind = "login", section = "登录"),
                        action("logout", kind = "clearSession", section = "登录"),
                    ),
            )

        assertTrue(presentation.named)
        assertTrue(presentation.showsAccountRow)
        assertFalse(presentation.showsStatus)
        assertTrue(presentation.headingActions.isEmpty())
        assertTrue(presentation.showsSettingsCard)
        assertTrue(presentation.cardSettings.isEmpty())
        assertNull(presentation.settingsHeader)
        assertNull(presentation.actionsHeader)
        assertTrue(presentation.listActions.isEmpty())
    }

    @Test
    fun `a generic account area renders the account row and no duplicate action list`() {
        val presentation =
            presentation(
                settings =
                    listOf(setting("account"), setting("password", type = "secret")),
                actions = listOf(action("login", kind = "login")),
            )

        assertFalse(presentation.named)
        assertTrue(presentation.showsAccountRow)
        assertEquals("设置", presentation.settingsHeader)
        assertTrue(presentation.showsSettingsCard)
        assertTrue(presentation.cardSettings.isEmpty())
        assertNull(presentation.actionsHeader)
        assertTrue(presentation.listActions.isEmpty())
    }

    @Test
    fun `a login without settings keeps its heading button and shows the session status`() {
        val presentation =
            presentation(
                settings = emptyList(),
                actions = listOf(action("login", kind = "login", section = "登录")),
            )

        assertTrue(presentation.named)
        assertFalse(presentation.showsAccountRow)
        assertTrue(presentation.showsStatus)
        assertEquals(listOf("login"), presentation.headingActions.map { it.id })
        assertFalse(presentation.showsSettingsCard)
    }

    @Test
    fun `a signed-in login disappears and a sign out appears`() {
        val login =
            presentation(
                settings = emptyList(),
                actions = listOf(action("login", kind = "login", section = "登录")),
                signedIn = true,
            )
        val logout =
            presentation(
                settings = emptyList(),
                actions = listOf(action("logout", kind = "logout", section = "登录")),
                signedIn = true,
            )
        val signedOutLogout =
            presentation(
                settings = emptyList(),
                actions = listOf(action("logout", kind = "logout", section = "登录")),
                signedIn = false,
            )

        assertTrue(login.headingActions.isEmpty())
        assertEquals(listOf("logout"), logout.headingActions.map { it.id })
        assertTrue(signedOutLogout.headingActions.isEmpty())
    }

    @Test
    fun `an account area keeps unrelated actions on the heading or in the list`() {
        val named =
            presentation(
                settings = listOf(setting("account", "登录")),
                actions =
                    listOf(
                        action("login", kind = "login", section = "登录"),
                        action("refresh", section = "登录"),
                    ),
            )
        val generic =
            presentation(
                settings = listOf(setting("account")),
                actions =
                    listOf(action("login", kind = "login"), action("refresh")),
            )

        assertEquals(listOf("refresh"), named.headingActions.map { it.id })
        assertEquals("操作", generic.actionsHeader)
        assertEquals(listOf("refresh"), generic.listActions.map { it.id })
    }

    @Test
    fun `picker actions stay off the area heading`() {
        val presentation =
            presentation(
                settings = listOf(setting("route", "线路")),
                actions =
                    listOf(
                        action("refresh", section = "线路"),
                        action("refreshDomains", section = "线路")
                            .copy(pickerSettingId = "route"),
                    ),
            )

        assertEquals(listOf("refresh"), presentation.headingActions.map { it.id })
    }
}
