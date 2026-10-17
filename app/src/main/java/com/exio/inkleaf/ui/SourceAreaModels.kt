package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionKind
import com.exio.inkleaf.plugin.PluginSettingDescriptor

/**
 * One area of the source detail screen: the settings the plugin grouped under a section, plus the
 * actions the plugin attached to that same section.
 *
 * A section that carries actions renders them on the area heading line, so an action sits next to
 * the fields it acts on (the login button beside the account and password fields). A null section
 * is the generic action area, which keeps the pre-section behaviour: settings with no heading and
 * actions as list rows.
 */
data class SourceArea(
    val section: String?,
    val settings: List<PluginSettingDescriptor>,
    val actions: List<PluginActionDescriptor>,
) {
    /** The heading shown above the area; section-less content falls back to the generic label. */
    val title: String
        get() = section ?: DEFAULT_ACTION_SECTION

    companion object {
        const val DEFAULT_ACTION_SECTION = "操作"
    }
}

/**
 * Groups settings and actions into areas by their declared section, preserving the order in which
 * each section first appears.
 *
 * Settings are scanned before actions so a section introduced by a setting keeps the author's
 * declared sequence; action-only sections (including the section-less generic area) follow in
 * action order. This mirrors the pre-section screen, where settings came first and the action list
 * came last.
 */
fun sourceAreas(
    settings: List<PluginSettingDescriptor>,
    actions: List<PluginActionDescriptor>,
): List<SourceArea> {
    val settingsBySection = LinkedHashMap<String?, MutableList<PluginSettingDescriptor>>()
    val actionsBySection = LinkedHashMap<String?, MutableList<PluginActionDescriptor>>()
    settings.forEach { descriptor ->
        settingsBySection.getOrPut(sectionKey(descriptor.section)) { mutableListOf() }.add(descriptor)
    }
    actions.forEach { action ->
        actionsBySection.getOrPut(sectionKey(action.section)) { mutableListOf() }.add(action)
    }
    val sections = LinkedHashSet<String?>()
    sections += settingsBySection.keys
    sections += actionsBySection.keys
    return sections.map { section ->
        SourceArea(
            section = section,
            settings = settingsBySection[section].orEmpty(),
            actions = actionsBySection[section].orEmpty(),
        )
    }
}

/** Blank section names are treated as absent, matching how the screen labels them. */
private fun sectionKey(section: String?): String? = section?.takeIf { it.isNotBlank() }

/** How an action declared inside an area is presented. */
enum class SourceActionEmphasis {
    /** The one clear next step, such as signing in. */
    PRIMARY,

    /** A supporting check, such as verifying the saved credentials. */
    TONAL,

    /** An ordinary or reversible action. */
    TEXT,
}

/**
 * Maps an action kind to its presentation. Only the protocol's kind enum is inspected, never
 * site-specific wording, so the host stays generic.
 */
fun sourceActionEmphasis(action: PluginActionDescriptor): SourceActionEmphasis =
    when (action.kind) {
        PluginActionKind.LOGIN -> SourceActionEmphasis.PRIMARY
        PluginActionKind.VERIFY_CREDENTIALS -> SourceActionEmphasis.TONAL
        else -> SourceActionEmphasis.TEXT
    }

/** True when an action clears the current session and should be hidden while signed out. */
fun PluginActionDescriptor.isSignOut(): Boolean = kind in PluginActionKind.signOutKinds

/**
 * The actions an area offers for the current session state. A login action disappears once signed
 * in, and a sign-out action stays hidden until then, so the heading never shows a step that cannot
 * apply. Other actions are always offered.
 */
fun visibleAreaActions(
    actions: List<PluginActionDescriptor>,
    signedIn: Boolean,
): List<PluginActionDescriptor> =
    actions.filter { action ->
        when {
            action.kind == PluginActionKind.LOGIN -> !signedIn
            action.isSignOut() -> signedIn
            else -> true
        }
    }

/** True when an area's heading should carry the signed-in state. */
fun areaShowsSessionStatus(area: SourceArea): Boolean =
    area.actions.any { it.kind in PluginActionKind.signInKinds || it.isSignOut() }
