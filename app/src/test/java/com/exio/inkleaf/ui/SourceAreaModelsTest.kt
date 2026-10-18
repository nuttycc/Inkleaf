package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginSettingDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAreaModelsTest {

    private fun setting(id: String, section: String? = null) =
        PluginSettingDescriptor(id = id, title = id, section = section)

    private fun action(id: String, kind: String = "action", section: String? = null) =
        PluginActionDescriptor(id = id, title = id, kind = kind, section = section)

    @Test
    fun `settings and actions sharing a section land in one area`() {
        val areas =
            sourceAreas(
                settings = listOf(setting("account", "登录"), setting("password", "登录")),
                actions =
                    listOf(
                        action("login", kind = "login", section = "登录"),
                        action("logout", kind = "clearSession", section = "登录"),
                    ),
            )

        assertEquals(1, areas.size)
        assertEquals("登录", areas.single().section)
        assertEquals(listOf("account", "password"), areas.single().settings.map { it.id })
        assertEquals(listOf("login", "logout"), areas.single().actions.map { it.id })
    }

    @Test
    fun `section order follows settings then action-only sections`() {
        val areas =
            sourceAreas(
                settings = listOf(setting("account", "登录"), setting("domain", "线路")),
                actions =
                    listOf(
                        action("refreshDomains", section = "线路"),
                        action("login", kind = "login", section = "登录"),
                        action("checkin"),
                    ),
            )

        assertEquals(listOf("登录", "线路", null), areas.map { it.section })
        assertEquals("操作", areas.last().title)
        assertEquals(listOf("checkin"), areas.last().actions.map { it.id })
    }

    @Test
    fun `actions without a section keep the generic area`() {
        val areas = sourceAreas(settings = emptyList(), actions = listOf(action("refresh")))

        assertEquals(1, areas.size)
        assertEquals(null, areas.single().section)
        assertEquals("操作", areas.single().title)
        assertTrue(areas.single().settings.isEmpty())
        assertEquals(listOf("refresh"), areas.single().actions.map { it.id })
    }

    @Test
    fun `an action-only section still forms its own area`() {
        val areas =
            sourceAreas(settings = emptyList(), actions = listOf(action("login", section = "登录")))

        assertEquals(listOf("登录"), areas.map { it.section })
        assertTrue(areas.single().settings.isEmpty())
        assertEquals(listOf("login"), areas.single().actions.map { it.id })
    }

    @Test
    fun `blank section names are treated as absent`() {
        val areas =
            sourceAreas(
                settings = listOf(setting("account", "   ")),
                actions = listOf(action("login", kind = "login")),
            )

        assertEquals(listOf<String?>(null), areas.map { it.section })
        assertEquals(listOf("account"), areas.single().settings.map { it.id })
        assertEquals(listOf("login"), areas.single().actions.map { it.id })
    }

    @Test
    fun `login is primary, verify is tonal, everything else is text`() {
        assertEquals(
            SourceActionEmphasis.PRIMARY,
            sourceActionEmphasis(action("login", kind = "login")),
        )
        assertEquals(
            SourceActionEmphasis.TONAL,
            sourceActionEmphasis(action("verify", kind = "verifyCredentials")),
        )
        assertEquals(
            SourceActionEmphasis.TEXT,
            sourceActionEmphasis(action("refresh", kind = "action")),
        )
    }

    @Test
    fun `sign out kinds are recognised`() {
        assertTrue(action("logout", kind = "logout").isSignOut())
        assertTrue(action("logout", kind = "clearSession").isSignOut())
        assertFalse(action("login", kind = "login").isSignOut())
        assertFalse(action("verify", kind = "verifyCredentials").isSignOut())
    }

    @Test
    fun `signed out areas offer login but hide sign out`() {
        val actions =
            listOf(
                action("login", kind = "login"),
                action("verify", kind = "verifyCredentials"),
                action("logout", kind = "clearSession"),
            )

        assertEquals(
            listOf("login", "verify"),
            visibleAreaActions(actions, signedIn = false).map { it.id },
        )
        assertEquals(
            listOf("verify", "logout"),
            visibleAreaActions(actions, signedIn = true).map { it.id },
        )
    }

    @Test
    fun `picker actions leave the heading and surface on their picker`() {
        val actions =
            listOf(
                action("refresh"),
                action("refreshDomains").copy(pickerSettingId = "route"),
            )

        assertEquals(
            listOf("refresh"),
            visibleAreaActions(actions, signedIn = false).map { it.id },
        )
        assertEquals(
            listOf("refreshDomains"),
            visiblePickerActions(actions, "route", signedIn = false).map { it.id },
        )
        assertTrue(visiblePickerActions(actions, "other", signedIn = false).isEmpty())
    }

    @Test
    fun `account actions move from the heading to the account sheet`() {
        val area =
            sourceAreas(
                    settings = listOf(setting("account", "登录"), setting("password", "登录")),
                    actions =
                        listOf(
                            action("login", kind = "login", section = "登录"),
                            action("logout", kind = "clearSession", section = "登录"),
                        ),
                )
                .single()

        assertTrue(areaUsesAccountSheet(area))
        assertTrue(headingActions(area, signedIn = false).isEmpty())
        assertTrue(headingActions(area, signedIn = true).isEmpty())
    }

    @Test
    fun `the account sheet keeps unrelated heading actions`() {
        val area =
            sourceAreas(
                    settings = listOf(setting("account", "登录")),
                    actions =
                        listOf(
                            action("login", kind = "login", section = "登录"),
                            action("refresh", section = "登录"),
                        ),
                )
                .single()

        assertEquals(listOf("refresh"), headingActions(area, signedIn = false).map { it.id })
    }

    @Test
    fun `a login action without settings keeps its heading button`() {
        val area =
            sourceAreas(
                    settings = emptyList(),
                    actions = listOf(action("login", kind = "login", section = "登录")),
                )
                .single()

        assertFalse(areaUsesAccountSheet(area))
        assertEquals(listOf("login"), headingActions(area, signedIn = false).map { it.id })
    }

    @Test
    fun `session status is shown only for areas that sign in or out`() {
        val authArea = sourceAreas(emptyList(), listOf(action("login", kind = "login"))).single()
        val plainArea = sourceAreas(emptyList(), listOf(action("refresh"))).single()

        assertTrue(areaShowsSessionStatus(authArea))
        assertFalse(areaShowsSessionStatus(plainArea))
    }
}
