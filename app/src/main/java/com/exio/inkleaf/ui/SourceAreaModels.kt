package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PluginActionDescriptor
import com.exio.inkleaf.plugin.PluginActionKind
import com.exio.inkleaf.plugin.PluginSessionState
import com.exio.inkleaf.plugin.PluginSettingDescriptor

/**
 * One area of the source detail screen: the settings the plugin grouped under a section, plus the
 * actions the plugin attached to that same section.
 *
 * A section that carries actions renders them on the area heading line, so an action sits next to
 * the fields it acts on (the refresh button beside the route field). Sign-in and sign-out actions
 * belong to the account sheet instead. A null section is the generic action area, which keeps the
 * pre-section behaviour: settings with no heading and actions as list rows.
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
        const val DEFAULT_SETTINGS_SECTION = "设置"
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
 * The actions an area offers on its heading. Picker actions stay with the list they act on and
 * are rendered on that list's sheet title instead.
 */
fun visibleAreaActions(
    actions: List<PluginActionDescriptor>,
    signedIn: Boolean,
): List<PluginActionDescriptor> =
    visibleActions(actions.filter { it.pickerSettingId == null }, signedIn)

/** The actions a setting's picker carries on its title line. */
fun visiblePickerActions(
    actions: List<PluginActionDescriptor>,
    settingId: String,
    signedIn: Boolean,
): List<PluginActionDescriptor> =
    visibleActions(actions.filter { it.pickerSettingId == settingId }, signedIn)

/** Session visibility, applied wherever an action is placed. */
private fun visibleActions(
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

/** True when an action signs the source in or out and therefore belongs to the account sheet. */
fun PluginActionDescriptor.isAccountAction(): Boolean =
    kind in PluginActionKind.signInKinds || isSignOut()

/**
 * True when the host shows the session state and the credentials through an account row and its
 * sheet. A login action without credential settings keeps its heading button, because the sheet
 * would have no fields to collect.
 */
fun areaUsesAccountSheet(area: SourceArea): Boolean =
    area.settings.isNotEmpty() && area.actions.any { it.isAccountAction() }

/**
 * True for the free-text fields the account sheet collects. Keeping the predicate in one place
 * guarantees the card and the sheet together show every setting of the area exactly once.
 */
fun PluginSettingDescriptor.isCredentialField(): Boolean =
    type == "text" || type == "secret" || secret

/** The settings the area's card shows; an account area leaves its credential fields to the sheet. */
fun cardSettings(area: SourceArea): List<PluginSettingDescriptor> =
    if (areaUsesAccountSheet(area)) area.settings.filterNot { it.isCredentialField() }
    else area.settings

/** Actions offered on the area heading; the account sheet owns the sign-in and sign-out actions. */
fun headingActions(
    area: SourceArea,
    signedIn: Boolean,
): List<PluginActionDescriptor> =
    visibleAreaActions(area.actions, signedIn).filterNot {
        areaUsesAccountSheet(area) && it.isAccountAction()
    }

/** True when an area's heading should carry the signed-in state. */
fun areaShowsSessionStatus(area: SourceArea): Boolean =
    area.actions.any { it.kind in PluginActionKind.signInKinds || it.isSignOut() }

/**
 * What one area renders on the source detail screen. The named and section-less layouts differ
 * only in these values, so the screen has one renderer instead of two branches.
 */
internal data class SourceAreaPresentation(
    val named: Boolean,
    val title: String,
    val showsStatus: Boolean,
    val headingActions: List<PluginActionDescriptor>,
    val settingsHeader: String?,
    val showsSettingsCard: Boolean,
    val cardSettings: List<PluginSettingDescriptor>,
    val showsAccountRow: Boolean,
    val actionsHeader: String?,
    val listActions: List<PluginActionDescriptor>,
)

/**
 * Resolves one area into the values the source detail screen renders. A named area keeps its
 * actions on the heading; a section-less area separates its settings and its action list with their
 * own headers. An account area keeps its credential fields and account actions in the sheet.
 */
internal fun sourceAreaPresentation(
    area: SourceArea,
    session: PluginSessionState,
): SourceAreaPresentation {
    val named = area.section != null
    val account = areaUsesAccountSheet(area)
    val actions = headingActions(area, session.signedIn)
    return SourceAreaPresentation(
        named = named,
        title = area.title,
        showsStatus = !account && areaShowsSessionStatus(area),
        headingActions = if (named) actions else emptyList(),
        settingsHeader =
            if (!named && area.settings.isNotEmpty()) SourceArea.DEFAULT_SETTINGS_SECTION else null,
        showsSettingsCard = area.settings.isNotEmpty(),
        cardSettings = cardSettings(area),
        showsAccountRow = account,
        actionsHeader = if (!named && actions.isNotEmpty()) area.title else null,
        listActions = if (named) emptyList() else actions,
    )
}
