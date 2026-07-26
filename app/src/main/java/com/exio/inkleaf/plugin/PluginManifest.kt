package com.exio.inkleaf.plugin

import kotlinx.serialization.Serializable

/** Stable metadata contract stored at the root of a plugin ZIP archive. */
@Serializable
data class PluginManifest(
    val manifestVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: String,
    val capabilities: List<String>,
    val description: String? = null,
    val icon: String? = null,
    val author: PluginAuthor? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val updateUrl: String? = null,
)

@Serializable
data class PluginAuthor(
    val name: String,
    val url: String? = null,
)

object PluginCapabilities {
    const val SEARCH = "search"
    const val DETAIL = "detail"
    const val CHAPTERS = "chapters"
    const val PAGES = "pages"
    const val BROWSE = "browse"
    const val HOME = "home"
    const val FILTERS = "filters"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val COMMENTS = "comments"
    const val DEEP_LINK = "deepLink"

    val required: Set<String> =
        setOf(SEARCH, DETAIL, CHAPTERS, PAGES)

    val knownOptional: Set<String> =
        setOf(BROWSE, HOME, FILTERS, LOGIN, SETTINGS, COMMENTS, DEEP_LINK)

    val declaredMethods: Set<String> = required + BROWSE
}

object PluginContract {
    const val SUPPORTED_MANIFEST_VERSION = 1
    val HOST_API_VERSION = ApiVersion(major = 1, minor = 1)
    const val MANIFEST_PATH = "manifest.json"
    const val ENTRY_PATH = "main.js"
    const val ASSET_PREFIX = "assets/"
}
