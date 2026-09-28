package com.exio.inkleaf.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One plugin package advertised by a remote plugin repository index. */
@Serializable
data class PluginRepoEntry(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val description: String? = null,
    val author: String? = null,
    val iconUrl: String? = null,
    @SerialName("pkgUrl") val packageUrl: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
)

/** Root document of a remote plugin repository index. */
@Serializable
data class PluginRepoIndex(
    val schemaVersion: Int,
    val repoName: String? = null,
    val apiVersion: String? = null,
    val plugins: List<PluginRepoEntry> = emptyList(),
)

object PluginRepoContract {
    const val SUPPORTED_SCHEMA_VERSION = 1

    /** Repository index bundled with the app; a static file published via GitHub raw. */
    const val DEFAULT_REPO_URL =
        "https://raw.githubusercontent.com/nuttycc/inkleaf-plugins/main/index.json"

    /** A persisted index is re-fetched automatically after this lifetime elapses. */
    const val CACHE_TTL_MS = 4L * 60L * 60L * 1000L
}

/** Parses a repository index document, rejecting schemas this build cannot trust. */
object PluginRepoIndexParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    fun parse(text: String): PluginRepoIndex {
        val index =
            try {
                json.decodeFromString<PluginRepoIndex>(text)
            } catch (error: IllegalArgumentException) {
                throw PluginRepoIndexFormatException("插件仓库索引格式无效", error)
            }
        if (index.schemaVersion != PluginRepoContract.SUPPORTED_SCHEMA_VERSION) {
            throw PluginRepoIndexFormatException(
                "插件仓库索引版本不受支持: ${index.schemaVersion}",
            )
        }
        if (index.plugins.isEmpty()) {
            throw PluginRepoIndexFormatException("插件仓库索引不包含任何插件")
        }
        val invalidEntry = index.plugins.firstOrNull { entry -> !entry.isValid() }
        if (invalidEntry != null) {
            throw PluginRepoIndexFormatException("插件条目缺少必要字段: ${invalidEntry.id.ifBlank { "<未命名>" }}")
        }
        return index
    }

    private fun PluginRepoEntry.isValid(): Boolean =
        id.isNotBlank() &&
            name.isNotBlank() &&
            version.isNotBlank() &&
            packageUrl.startsWith("https://")
}

/** Raised when a repository index cannot be trusted or understood. */
class PluginRepoIndexFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
