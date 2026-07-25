package com.exio.inkleaf.plugin

import kotlinx.serialization.Serializable

/** Persistent lifecycle state for one installed plugin id. */
@Serializable
data class PluginState(
    val pluginId: String,
    val activeVersion: String? = null,
    val previousVersion: String? = null,
    val disabled: Boolean = false,
    val health: PluginHealth = PluginHealth.HEALTHY,
    val fatalFailureTimesMs: List<Long> = emptyList(),
    val versions: List<PluginVersionRecord> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class PluginVersionRecord(
    val version: String,
    val sha256: String,
    val compatible: Boolean,
    val installedAtMs: Long,
)

enum class PluginHealth {
    HEALTHY,
    RUNTIME_UNHEALTHY,
}

enum class PluginInstallStatus {
    INSTALLED,
    ALREADY_INSTALLED,
    REJECTED,
}

enum class PluginInstallErrorCode {
    NOT_A_FILE,
    PACKAGE_TOO_LARGE,
    VALIDATION_FAILED,
    VERSION_CONFLICT,
    INCOMPATIBLE_VERSION,
    STORAGE_FAILURE,
}

data class PluginInstallResult(
    val status: PluginInstallStatus,
    val pluginId: String? = null,
    val version: String? = null,
    val sha256: String? = null,
    val activatable: Boolean = false,
    val validation: PluginValidationResult? = null,
    val errorCode: PluginInstallErrorCode? = null,
    val errorMessage: String? = null,
)

class PluginInstallException(
    val code: PluginInstallErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class InstalledPlugin(
    val state: PluginState,
    val directory: java.io.File,
    val activeDirectory: java.io.File?,
)

internal object PluginStorageLimits {
    const val MAX_PACKAGE_BYTES = 64L * 1024L * 1024L
    const val MAX_ASSET_BYTES = 32L * 1024L * 1024L
    const val MAX_ASSET_TOTAL_BYTES = 128L * 1024L * 1024L
    const val MAX_RETAINED_VERSIONS = 3
}
