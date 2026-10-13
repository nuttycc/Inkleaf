package com.exio.inkleaf.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The single release entry in the remote update manifest. */
@Serializable
data class AppUpdateEntry(
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
    val release: AppUpdateEntry? = null,
)

/** Result of comparing the remote manifest against the running app. */
sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult

    data class UpdateAvailable(val entry: AppUpdateEntry) : AppUpdateCheckResult

    data class Error(val message: String) : AppUpdateCheckResult
}

object AppUpdateManifestParser {
    const val SUPPORTED_SCHEMA_VERSION = 2

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
        manifest.release?.validate()
        return manifest
    }

    private fun AppUpdateEntry.validate() {
        if (version.isBlank() || versionCode <= 0 || !apkUrl.startsWith("https://")) {
            throw AppUpdateManifestException("更新清单 release 通道缺少必要字段")
        }
    }
}

class AppUpdateManifestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
