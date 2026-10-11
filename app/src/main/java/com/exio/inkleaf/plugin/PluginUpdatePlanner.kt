package com.exio.inkleaf.plugin

/** Update disposition for one repository entry compared against the installed plugins. */
sealed interface PluginEntryState {
    /** No plugin with this id is installed. */
    data object NotInstalled : PluginEntryState

    /** Installed, and the repository advertises a strictly newer version. */
    data class UpdateAvailable(
        val installedVersion: String,
        val repositoryVersion: String,
    ) : PluginEntryState

    /** Installed at the repository version, a newer one, or with that version already present. */
    data object UpToDate : PluginEntryState

    /** Installed, but the versions cannot be ordered (invalid SemVer, or nothing active). */
    data object Unknown : PluginEntryState
}

/** One repository entry the user can update, paired with the versions involved. */
data class PluginUpdateCandidate(
    val entry: PluginRepoEntry,
    val installedVersion: String,
    val repositoryVersion: String,
)

/**
 * Compares a repository index against the installed plugins.
 *
 * An update is offered only when both versions parse as SemVer and the repository version is
 * strictly newer. An unparsable version, or one already present on disk, never prompts the user:
 * the latter keeps an installed-but-unactivatable version from producing a prompt that can never
 * be satisfied.
 */
object PluginUpdatePlanner {
    fun state(entry: PluginRepoEntry, installed: InstalledPlugin?): PluginEntryState {
        if (installed == null) return PluginEntryState.NotInstalled
        if (installed.state.versions.any { it.version == entry.version }) {
            return PluginEntryState.UpToDate
        }
        val installedVersion =
            installed.state.activeVersion ?: return PluginEntryState.Unknown
        val installedSemVer = SemVer.parse(installedVersion) ?: return PluginEntryState.Unknown
        val repositorySemVer = SemVer.parse(entry.version) ?: return PluginEntryState.Unknown
        return if (repositorySemVer > installedSemVer) {
            PluginEntryState.UpdateAvailable(installedVersion, entry.version)
        } else {
            PluginEntryState.UpToDate
        }
    }

    /** The entries whose repository version is strictly newer than what is installed. */
    fun updates(
        entries: List<PluginRepoEntry>,
        installed: List<InstalledPlugin>,
    ): List<PluginUpdateCandidate> =
        entries.mapNotNull { entry ->
            val plugin = installed.firstOrNull { it.state.pluginId == entry.id }
            when (val state = state(entry, plugin)) {
                is PluginEntryState.UpdateAvailable ->
                    PluginUpdateCandidate(entry, state.installedVersion, state.repositoryVersion)
                else -> null
            }
        }
}
