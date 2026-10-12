package com.exio.inkleaf.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One release channel entry in the remote update manifest. */
@Serializable
data class AppUpdateChannelEntry(
    val version: String,
    val versionCode: Long,
    val apkUrl: String,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
)

/** Root document of the remote app update manifest. */
@Serializable
data class AppUpdateManifest(
    val schemaVersion: Int,
    val stable: AppUpdateChannelEntry? = null,
    val preview: AppUpdateChannelEntry? = null,
)

/** Channels the user can subscribe to. */
enum class AppUpdateChannel {
    @SerialName("stable") STABLE,
    @SerialName("preview") PREVIEW,
}

/** Result of comparing the remote manifest against the running app. */
sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult

    data class UpdateAvailable(
        val entry: AppUpdateChannelEntry,
        val channel: AppUpdateChannel,
    ) : AppUpdateCheckResult

    data class Error(val message: String) : AppUpdateCheckResult
}

object AppUpdateManifestParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AppUpdateManifest {
        val manifest =
            try {
                json.decodeFromString<AppUpdateManifest>(text)
            } catch (error: IllegalArgumentException) {
                throw AppUpdateManifestException("更新清单格式无效", error)
            }
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw AppUpdateManifestException("更新清单版本不受支持: ${manifest.schemaVersion}")
        }
        manifest.stable?.validate("stable")
        manifest.preview?.validate("preview")
        return manifest
    }

    private fun AppUpdateChannelEntry.validate(channel: String) {
        if (version.isBlank() || versionCode <= 0 || !apkUrl.startsWith("https://")) {
            throw AppUpdateManifestException("更新清单 $channel 通道缺少必要字段")
        }
    }
}

class AppUpdateManifestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
