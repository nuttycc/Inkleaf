package com.exio.inkleaf.plugin

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class PluginIssueKind {
    ERROR,
    WARNING,
    INCOMPATIBLE,
}

enum class PluginIssueCode {
    NOT_A_FILE,
    INVALID_ARCHIVE,
    DUPLICATE_ENTRY,
    UNSAFE_ENTRY_PATH,
    UNEXPECTED_ENTRY,
    MISSING_MANIFEST,
    MISSING_ENTRY,
    ENTRY_TOO_LARGE,
    INVALID_UTF8,
    INVALID_MANIFEST,
    UNSUPPORTED_MANIFEST_VERSION,
    INVALID_ID,
    INVALID_NAME,
    INVALID_VERSION,
    INVALID_API_VERSION,
    API_MAJOR_MISMATCH,
    API_MINOR_TOO_NEW,
    DUPLICATE_CAPABILITY,
    INVALID_CAPABILITY,
    MISSING_REQUIRED_CAPABILITY,
    UNKNOWN_OPTIONAL_CAPABILITY,
    MISSING_ICON,
}

data class PluginValidationIssue(
    val kind: PluginIssueKind,
    val code: PluginIssueCode,
    val message: String,
    val path: String? = null,
)

data class ValidatedPluginPackage(
    val manifest: PluginManifest,
    val mainScript: String,
    val entryNames: Set<String>,
)

data class PluginValidationResult(
    val packageContent: ValidatedPluginPackage?,
    val issues: List<PluginValidationIssue>,
) {
    val installable: Boolean
        get() = issues.none { it.kind == PluginIssueKind.ERROR }

    val activatable: Boolean
        get() =
            installable &&
                issues.none { it.kind == PluginIssueKind.INCOMPATIBLE }

    val errors: List<PluginValidationIssue>
        get() = issues.filter { it.kind == PluginIssueKind.ERROR }

    val warnings: List<PluginValidationIssue>
        get() = issues.filter { it.kind == PluginIssueKind.WARNING }

    val incompatibilities: List<PluginValidationIssue>
        get() = issues.filter { it.kind == PluginIssueKind.INCOMPATIBLE }
}

/** Validates the static structure and compatibility of a plugin ZIP archive. */
class PluginPackageValidator(
    private val supportedManifestVersion: Int = PluginContract.SUPPORTED_MANIFEST_VERSION,
    private val hostApiVersion: ApiVersion = PluginContract.HOST_API_VERSION,
    private val maxManifestBytes: Long = 256 * 1024,
    private val maxMainScriptBytes: Long = 8 * 1024 * 1024,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
        }

    fun validate(file: File): PluginValidationResult {
        if (!file.isFile) {
            return PluginValidationResult(
                packageContent = null,
                issues =
                    listOf(
                        issue(
                            PluginIssueKind.ERROR,
                            PluginIssueCode.NOT_A_FILE,
                            "Plugin package is not a regular file",
                        )
                    ),
            )
        }

        return try {
            ZipFile(file).use { zip -> validateZip(zip) }
        } catch (error: IOException) {
            PluginValidationResult(
                packageContent = null,
                issues =
                    listOf(
                        issue(
                            PluginIssueKind.ERROR,
                            PluginIssueCode.INVALID_ARCHIVE,
                            "Unable to read plugin archive: ${error.message ?: error::class.java.simpleName}",
                        )
                    ),
            )
        }
    }

    private fun validateZip(zip: ZipFile): PluginValidationResult {
        val issues = mutableListOf<PluginValidationIssue>()
        val entries = linkedSetOf<String>()
        var manifestBytes: ByteArray? = null
        var mainBytes: ByteArray? = null

        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            val name = entry.name
            if (!entries.add(name)) {
                issues += issue(
                    PluginIssueKind.ERROR,
                    PluginIssueCode.DUPLICATE_ENTRY,
                    "Archive contains duplicate entry: $name",
                    name,
                )
                continue
            }
            if (!PluginPaths.isSafeArchivePath(name)) {
                issues += issue(
                    PluginIssueKind.ERROR,
                    PluginIssueCode.UNSAFE_ENTRY_PATH,
                    "Archive entry has an unsafe path",
                    name,
                )
                continue
            }
            if (entry.isDirectory) {
                if (name != "assets/" && !name.startsWith(PluginContract.ASSET_PREFIX)) {
                    issues += issue(
                        PluginIssueKind.ERROR,
                        PluginIssueCode.UNEXPECTED_ENTRY,
                        "Only assets directories may be present",
                        name,
                    )
                }
                continue
            }

            when {
                name == PluginContract.MANIFEST_PATH ->
                    manifestBytes = readEntry(zip, entry, maxManifestBytes, issues)
                name == PluginContract.ENTRY_PATH ->
                    mainBytes = readEntry(zip, entry, maxMainScriptBytes, issues)
                name.startsWith(PluginContract.ASSET_PREFIX) -> Unit
                else ->
                    issues += issue(
                        PluginIssueKind.ERROR,
                        PluginIssueCode.UNEXPECTED_ENTRY,
                        "Only manifest.json, main.js and assets are allowed",
                        name,
                    )
            }
        }

        if (!entries.contains(PluginContract.MANIFEST_PATH)) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.MISSING_MANIFEST,
                "Archive is missing manifest.json",
                PluginContract.MANIFEST_PATH,
            )
        }
        if (!entries.contains(PluginContract.ENTRY_PATH)) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.MISSING_ENTRY,
                "Archive is missing main.js",
                PluginContract.ENTRY_PATH,
            )
        }

        val manifestText = manifestBytes?.let { decodeUtf8(it, issues, PluginContract.MANIFEST_PATH) }
        val mainScript = mainBytes?.let { decodeUtf8(it, issues, PluginContract.ENTRY_PATH) }
        val manifest = manifestText?.let { decodeManifest(it, issues) }
        if (manifest != null) validateManifest(manifest, entries, issues)

        val content =
            if (manifest != null && mainScript != null) {
                ValidatedPluginPackage(manifest, mainScript, entries)
            } else {
                null
            }
        return PluginValidationResult(content, issues.toList())
    }

    private fun decodeManifest(
        text: String,
        issues: MutableList<PluginValidationIssue>,
    ): PluginManifest? =
        try {
            json.decodeFromString<PluginManifest>(text)
        } catch (error: SerializationException) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_MANIFEST,
                "manifest.json is not a valid plugin manifest: ${error.message ?: "parse error"}",
                PluginContract.MANIFEST_PATH,
            )
            null
        }

    private fun validateManifest(
        manifest: PluginManifest,
        entries: Set<String>,
        issues: MutableList<PluginValidationIssue>,
    ) {
        if (manifest.manifestVersion != supportedManifestVersion) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.UNSUPPORTED_MANIFEST_VERSION,
                "Unsupported manifestVersion: ${manifest.manifestVersion}",
            )
        }
        if (!PluginIds.isValid(manifest.id)) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_ID,
                "Plugin id must be a lowercase reverse-domain identifier",
            )
        }
        if (manifest.name.trim().isEmpty() || manifest.name.length > 128) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_NAME,
                "Plugin name must contain 1..128 characters",
            )
        }
        if (SemVer.parse(manifest.version) == null) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_VERSION,
                "Plugin version must be strict SemVer",
            )
        }

        val apiVersion = ApiVersion.parse(manifest.apiVersion)
        if (apiVersion == null) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_API_VERSION,
                "apiVersion must use major.minor format",
            )
        } else {
            when (apiVersion.compatibilityWith(hostApiVersion)) {
                PluginCompatibility.COMPATIBLE -> Unit
                PluginCompatibility.API_MAJOR_MISMATCH ->
                    issues += issue(
                        PluginIssueKind.INCOMPATIBLE,
                        PluginIssueCode.API_MAJOR_MISMATCH,
                        "Plugin API ${apiVersion} is incompatible with host API $hostApiVersion",
                    )
                PluginCompatibility.API_MINOR_TOO_NEW ->
                    issues += issue(
                        PluginIssueKind.INCOMPATIBLE,
                        PluginIssueCode.API_MINOR_TOO_NEW,
                        "Plugin API ${apiVersion} is newer than host API $hostApiVersion",
                    )
            }
        }

        val capabilitySet = manifest.capabilities.toSet()
        if (capabilitySet.size != manifest.capabilities.size) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.DUPLICATE_CAPABILITY,
                "Manifest capabilities must not contain duplicates",
            )
        }
        if (manifest.capabilities.any { it.isBlank() || !it.matches(CAPABILITY_PATTERN) }) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_CAPABILITY,
                "Capability names must be non-empty ASCII identifiers",
            )
        }
        val missingRequired = PluginCapabilities.required - capabilitySet
        if (missingRequired.isNotEmpty()) {
            issues += issue(
                PluginIssueKind.INCOMPATIBLE,
                PluginIssueCode.MISSING_REQUIRED_CAPABILITY,
                "Missing required capabilities: ${missingRequired.sorted().joinToString(", ")}",
            )
        }
        manifest.capabilities
            .filter { it !in PluginCapabilities.required && it !in PluginCapabilities.knownOptional }
            .distinct()
            .forEach { capability ->
                issues += issue(
                    PluginIssueKind.WARNING,
                    PluginIssueCode.UNKNOWN_OPTIONAL_CAPABILITY,
                    "Unknown optional capability will be hidden: $capability",
                )
            }

        manifest.icon?.let { icon ->
            if (icon !in entries) {
                issues += issue(
                    PluginIssueKind.WARNING,
                    PluginIssueCode.MISSING_ICON,
                    "Declared icon is not present in the archive",
                    icon,
                )
            } else if (!icon.startsWith(PluginContract.ASSET_PREFIX) || !PluginPaths.isSafeArchivePath(icon)) {
                issues += issue(
                    PluginIssueKind.ERROR,
                    PluginIssueCode.UNSAFE_ENTRY_PATH,
                    "Manifest icon must point to a safe assets path",
                    icon,
                )
            }
        }
    }

    private fun readEntry(
        zip: ZipFile,
        entry: ZipEntry,
        maxBytes: Long,
        issues: MutableList<PluginValidationIssue>,
    ): ByteArray? {
        if (entry.size > maxBytes) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.ENTRY_TOO_LARGE,
                "Archive entry exceeds the allowed size of $maxBytes bytes",
                entry.name,
            )
            return null
        }
        return try {
            zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        issues += issue(
                            PluginIssueKind.ERROR,
                            PluginIssueCode.ENTRY_TOO_LARGE,
                            "Archive entry exceeds the allowed size of $maxBytes bytes",
                            entry.name,
                        )
                        return null
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (error: IOException) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_ARCHIVE,
                "Unable to read archive entry: ${error.message ?: error::class.java.simpleName}",
                entry.name,
            )
            null
        }
    }

    private fun decodeUtf8(
        bytes: ByteArray,
        issues: MutableList<PluginValidationIssue>,
        path: String,
    ): String? =
        try {
            val decoder =
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            issues += issue(
                PluginIssueKind.ERROR,
                PluginIssueCode.INVALID_UTF8,
                "Archive entry is not valid UTF-8",
                path,
            )
            null
        }

    private fun issue(
        kind: PluginIssueKind,
        code: PluginIssueCode,
        message: String,
        path: String? = null,
    ) = PluginValidationIssue(kind, code, message, path)

    private companion object {
        val CAPABILITY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
    }
}
