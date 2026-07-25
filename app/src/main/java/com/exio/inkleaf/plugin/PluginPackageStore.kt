package com.exio.inkleaf.plugin

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json

/**
 * File-backed plugin package lifecycle store.
 *
 * The store is deliberately independent from Android Context so the install state machine can be
 * exercised with a temporary JVM directory. Callers provide an app-private `plugins` root.
 */
class PluginPackageStore(
    private val pluginsRoot: File,
    private val validator: PluginPackageValidator = PluginPackageValidator(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val json: Json = defaultJson,
) {
    private val lock = Any()

    init {
        require(pluginsRoot.isAbsolute) { "pluginsRoot must be absolute" }
    }

    fun install(packageFile: File, activate: Boolean = false): PluginInstallResult =
        synchronized(lock) {
            installLocked(packageFile, activate)
        }

    fun activate(pluginId: String, version: String): InstalledPlugin {
        requireValidPluginId(pluginId)
        requireValidVersion(version)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            val state = readState(directory) ?: throw PluginInstallException(
                PluginInstallErrorCode.STORAGE_FAILURE,
                "Plugin state does not exist: $pluginId",
            )
            val record = state.versions.firstOrNull { it.version == version }
                ?: throw PluginInstallException(
                    PluginInstallErrorCode.STORAGE_FAILURE,
                    "Plugin version is not installed: $pluginId@$version",
                )
            if (!record.compatible) {
                throw PluginInstallException(
                    PluginInstallErrorCode.INCOMPATIBLE_VERSION,
                    "Plugin version is incompatible with the host API: $pluginId@$version",
                )
            }
            val versionDirectory = versionDirectory(directory, version)
            requireInstalledVersion(versionDirectory)
            val nextState = state.copy(
                activeVersion = version,
                previousVersion = state.activeVersion?.takeIf { it != version } ?: state.previousVersion,
                disabled = false,
                health = PluginHealth.HEALTHY,
                fatalFailureTimesMs = emptyList(),
                updatedAtMs = clockMs(),
            )
            writeStateAtomically(directory, nextState)
            val prunedState = pruneVersions(directory, nextState)
            installedPlugin(directory, prunedState)
        }
    }

    fun rollback(pluginId: String): InstalledPlugin? {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            val state = readState(directory) ?: return@synchronized null
            val previous = state.previousVersion ?: return@synchronized null
            val previousRecord = state.versions.firstOrNull { it.version == previous }
                ?: return@synchronized null
            if (!previousRecord.compatible) return@synchronized null
            val previousDirectory = versionDirectory(directory, previous)
            if (!previousDirectory.isDirectory) return@synchronized null
            val nextState = state.copy(
                activeVersion = previous,
                previousVersion = state.activeVersion?.takeIf { it != previous },
                disabled = false,
                health = PluginHealth.HEALTHY,
                fatalFailureTimesMs = emptyList(),
                updatedAtMs = clockMs(),
            )
            writeStateAtomically(directory, nextState)
            installedPlugin(directory, nextState)
        }
    }

    fun deactivate(pluginId: String, expectedVersion: String? = null): InstalledPlugin? {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            val state = readState(directory) ?: return@synchronized null
            if (expectedVersion != null && state.activeVersion != expectedVersion) return@synchronized installedPlugin(directory, state)
            val nextState = state.copy(
                activeVersion = null,
                previousVersion = state.previousVersion?.takeIf { it != expectedVersion },
                updatedAtMs = clockMs(),
            )
            writeStateAtomically(directory, nextState)
            installedPlugin(directory, nextState)
        }
    }

    fun setEnabled(pluginId: String, enabled: Boolean): InstalledPlugin? {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            val state = readState(directory) ?: return@synchronized null
            val nextState = state.copy(
                disabled = !enabled,
                updatedAtMs = clockMs(),
            )
            writeStateAtomically(directory, nextState)
            installedPlugin(directory, nextState)
        }
    }

    fun recordFatalFailure(pluginId: String, nowMs: Long = clockMs()): InstalledPlugin? {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            val state = readState(directory) ?: return@synchronized null
            val recent = state.fatalFailureTimesMs.filter { nowMs - it < HEALTH_WINDOW_MS } + nowMs
            val nextState = state.copy(
                health = if (recent.size >= MAX_FATAL_FAILURES) {
                    PluginHealth.RUNTIME_UNHEALTHY
                } else {
                    state.health
                },
                fatalFailureTimesMs = recent.takeLast(MAX_FATAL_FAILURES),
                updatedAtMs = nowMs,
            )
            writeStateAtomically(directory, nextState)
            installedPlugin(directory, nextState)
        }
    }

    fun clearHealth(pluginId: String): InstalledPlugin? {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            val state = readState(directory) ?: return@synchronized null
            val nextState = state.copy(
                health = PluginHealth.HEALTHY,
                fatalFailureTimesMs = emptyList(),
                updatedAtMs = clockMs(),
            )
            writeStateAtomically(directory, nextState)
            installedPlugin(directory, nextState)
        }
    }

    fun get(pluginId: String): InstalledPlugin? {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            readState(directory)?.let { installedPlugin(directory, it) }
        }
    }

    fun list(): List<InstalledPlugin> = synchronized(lock) {
        if (!pluginsRoot.isDirectory) return@synchronized emptyList()
        pluginsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && PluginIds.isValid(it.name) }
            .mapNotNull { directory -> readState(directory)?.let { installedPlugin(directory, it) } }
            .sortedBy { it.state.pluginId }
    }

    fun uninstall(pluginId: String): Boolean {
        requireValidPluginId(pluginId)
        return synchronized(lock) {
            val directory = pluginDirectory(pluginId)
            if (!directory.exists()) return@synchronized false
            directory.deleteRecursively()
        }
    }

    fun activeEntryFile(pluginId: String): File? = synchronized(lock) {
        get(pluginId)?.activeDirectory?.resolve(PluginContract.ENTRY_PATH)?.takeIf { it.isFile }
    }

    private fun installLocked(packageFile: File, activate: Boolean): PluginInstallResult {
        if (!packageFile.isFile) {
            return rejected(PluginInstallErrorCode.NOT_A_FILE, "Plugin package is not a regular file")
        }
        if (packageFile.length() > PluginStorageLimits.MAX_PACKAGE_BYTES) {
            return rejected(
                PluginInstallErrorCode.PACKAGE_TOO_LARGE,
                "Plugin package exceeds ${PluginStorageLimits.MAX_PACKAGE_BYTES} bytes",
            )
        }

        val validation = validator.validate(packageFile)
        val content = validation.packageContent
        if (!validation.installable || content == null) {
            return PluginInstallResult(
                status = PluginInstallStatus.REJECTED,
                validation = validation,
                errorCode = PluginInstallErrorCode.VALIDATION_FAILED,
                errorMessage = "Plugin package failed validation",
            )
        }

        val manifest = content.manifest
        val digest = sha256(packageFile)
        val directory = pluginDirectory(manifest.id)
        val state = readState(directory) ?: PluginState(pluginId = manifest.id)
        val existing = state.versions.firstOrNull { it.version == manifest.version }
        if (existing != null) {
            if (!existing.sha256.equals(digest, ignoreCase = true)) {
                return PluginInstallResult(
                    status = PluginInstallStatus.REJECTED,
                    pluginId = manifest.id,
                    version = manifest.version,
                    sha256 = digest,
                    activatable = validation.activatable,
                    validation = validation,
                    errorCode = PluginInstallErrorCode.VERSION_CONFLICT,
                    errorMessage = "The same plugin version is already installed with a different SHA-256",
                )
            }
            if (activate && validation.activatable) activate(manifest.id, manifest.version)
            return PluginInstallResult(
                status = PluginInstallStatus.ALREADY_INSTALLED,
                pluginId = manifest.id,
                version = manifest.version,
                sha256 = digest,
                activatable = validation.activatable,
                validation = validation,
            )
        }

        val staging = stagingDirectory(directory, manifest.version)
        var installedTarget: File? = null
        try {
            val stagingParent = requireNotNull(staging.parentFile)
            if (!stagingParent.mkdirs() && !stagingParent.isDirectory) {
                throw IOException("Unable to create plugin staging directory")
            }
            if (staging.exists()) staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Unable to create plugin staging directory")
            extractPackage(packageFile, staging)

            val versionsDirectory = directory.resolve("versions")
            if (!versionsDirectory.mkdirs() && !versionsDirectory.isDirectory) {
                throw IOException("Unable to create plugin versions directory")
            }
            val target = versionDirectory(directory, manifest.version)
            if (target.exists()) {
                throw PluginInstallException(
                    PluginInstallErrorCode.VERSION_CONFLICT,
                    "Plugin version appeared while installing: ${manifest.id}@${manifest.version}",
                )
            }
            moveAtomically(staging, target)
            installedTarget = target

            val record = PluginVersionRecord(
                version = manifest.version,
                sha256 = digest,
                compatible = validation.activatable,
                installedAtMs = clockMs(),
            )
            val nextState = state.copy(
                versions = (state.versions + record).sortedWith(
                    compareByDescending<PluginVersionRecord> { requireNotNull(SemVer.parse(it.version)) }
                        .thenByDescending { it.installedAtMs }
                ),
                updatedAtMs = clockMs(),
            )
            writeStateAtomically(directory, nextState)
            installedTarget = null

            if (activate && validation.activatable) {
                activate(manifest.id, manifest.version)
            } else {
                pruneVersions(directory, nextState)
            }
            return PluginInstallResult(
                status = PluginInstallStatus.INSTALLED,
                pluginId = manifest.id,
                version = manifest.version,
                sha256 = digest,
                activatable = validation.activatable,
                validation = validation,
            )
        } catch (error: PluginInstallException) {
            staging.deleteRecursively()
            installedTarget?.deleteRecursively()
            return PluginInstallResult(
                status = PluginInstallStatus.REJECTED,
                pluginId = manifest.id,
                version = manifest.version,
                sha256 = digest,
                activatable = validation.activatable,
                validation = validation,
                errorCode = error.code,
                errorMessage = error.message,
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            installedTarget?.deleteRecursively()
            return PluginInstallResult(
                status = PluginInstallStatus.REJECTED,
                pluginId = manifest.id,
                version = manifest.version,
                sha256 = digest,
                activatable = validation.activatable,
                validation = validation,
                errorCode = PluginInstallErrorCode.STORAGE_FAILURE,
                errorMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun extractPackage(packageFile: File, staging: File) {
        var assetTotal = 0L
        ZipFile(packageFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!PluginPaths.isSafeArchivePath(entry.name)) {
                    throw PluginInstallException(
                        PluginInstallErrorCode.VALIDATION_FAILED,
                        "Unsafe archive entry: ${entry.name}",
                    )
                }
                if (!entry.name.equals(PluginContract.MANIFEST_PATH) &&
                    !entry.name.equals(PluginContract.ENTRY_PATH) &&
                    !entry.name.startsWith(PluginContract.ASSET_PREFIX)
                ) {
                    throw PluginInstallException(
                        PluginInstallErrorCode.VALIDATION_FAILED,
                        "Unexpected archive entry: ${entry.name}",
                    )
                }
                val target = staging.resolve(entry.name)
                ensureChildPath(staging, target)
                target.parentFile?.mkdirs()
                val entryLimit = if (entry.name.startsWith(PluginContract.ASSET_PREFIX)) {
                    PluginStorageLimits.MAX_ASSET_BYTES
                } else {
                    Long.MAX_VALUE
                }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > entryLimit) {
                                throw PluginInstallException(
                                    PluginInstallErrorCode.PACKAGE_TOO_LARGE,
                                    "Archive entry exceeds its size limit: ${entry.name}",
                                )
                            }
                            if (entry.name.startsWith(PluginContract.ASSET_PREFIX)) assetTotal += count
                            if (assetTotal > PluginStorageLimits.MAX_ASSET_TOTAL_BYTES) {
                                throw PluginInstallException(
                                    PluginInstallErrorCode.PACKAGE_TOO_LARGE,
                                    "Plugin assets exceed the total size limit",
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    private fun pruneVersions(directory: File, state: PluginState): PluginState {
        val keep = linkedSetOf<String>()
        state.activeVersion?.let(keep::add)
        state.previousVersion?.let(keep::add)
        state.versions
            .sortedWith(
                compareByDescending<PluginVersionRecord> { requireNotNull(SemVer.parse(it.version)) }
                    .thenByDescending { it.installedAtMs }
            )
            .forEach { version ->
                if (keep.size < PluginStorageLimits.MAX_RETAINED_VERSIONS) keep += version.version
            }
        val toRemove = state.versions.map { it.version }.filter { it !in keep }
        toRemove.forEach { versionDirectory(directory, it).deleteRecursively() }
        if (toRemove.isEmpty()) return state
        val pruned = state.copy(
            versions = state.versions.filter { it.version !in toRemove },
            updatedAtMs = clockMs(),
        )
        writeStateAtomically(directory, pruned)
        return pruned
    }

    private fun installedPlugin(directory: File, state: PluginState): InstalledPlugin =
        InstalledPlugin(
            state = state,
            directory = directory,
            activeDirectory = state.activeVersion?.let { versionDirectory(directory, it) },
        )

    private fun readState(directory: File): PluginState? {
        val file = directory.resolve(STATE_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<PluginState>(file.readText(StandardCharsets.UTF_8)) }
            .getOrElse { throw PluginInstallException(PluginInstallErrorCode.STORAGE_FAILURE, "Invalid plugin state: ${file.path}", it) }
    }

    private fun writeStateAtomically(directory: File, state: PluginState) {
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Unable to create ${directory.path}")
        val temp = directory.resolve("$STATE_FILE.tmp-${UUID.randomUUID()}")
        try {
            temp.writeText(json.encodeToString(PluginState.serializer(), state), StandardCharsets.UTF_8)
            moveAtomically(temp, directory.resolve(STATE_FILE))
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun pluginDirectory(pluginId: String): File {
        requireValidPluginId(pluginId)
        return pluginsRoot.resolve(pluginId)
    }

    private fun versionDirectory(pluginDirectory: File, version: String): File =
        pluginDirectory.resolve("versions").resolve(version)

    private fun stagingDirectory(pluginDirectory: File, version: String): File =
        pluginDirectory.resolve("staging").resolve("$version-${UUID.randomUUID()}")

    private fun requireInstalledVersion(directory: File) {
        if (!directory.isDirectory || !directory.resolve(PluginContract.ENTRY_PATH).isFile ||
            !directory.resolve(PluginContract.MANIFEST_PATH).isFile
        ) {
            throw PluginInstallException(
                PluginInstallErrorCode.STORAGE_FAILURE,
                "Installed plugin version is incomplete: ${directory.path}",
            )
        }
    }

    private fun ensureChildPath(root: File, child: File) {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        if (!childPath.startsWith(rootPath)) {
            throw PluginInstallException(
                PluginInstallErrorCode.VALIDATION_FAILED,
                "Archive entry escapes staging directory",
            )
        }
    }

    private fun moveAtomically(source: File, target: File) {
        target.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Unable to create ${parent.path}")
        }
        try {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (source.exists()) source.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rejected(code: PluginInstallErrorCode, message: String) =
        PluginInstallResult(
            status = PluginInstallStatus.REJECTED,
            errorCode = code,
            errorMessage = message,
        )

    private fun requireValidPluginId(pluginId: String) {
        require(PluginIds.isValid(pluginId)) { "Invalid plugin id: $pluginId" }
    }

    private fun requireValidVersion(version: String) {
        require(SemVer.parse(version) != null) { "Invalid plugin version: $version" }
    }

    private companion object {
        const val STATE_FILE = "state.json"
        const val MAX_FATAL_FAILURES = 3
        const val HEALTH_WINDOW_MS = 10L * 60L * 1000L
        val defaultJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

object PluginIds {
    private val pattern =
        Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")

    fun isValid(value: String): Boolean = value.length <= 255 && pattern.matches(value)
}

internal object PluginPaths {
    fun isSafeArchivePath(value: String): Boolean {
        if (value.isEmpty() || value.startsWith('/') || value.contains('\\') || value.contains(':')) return false
        val path = value.removeSuffix("/")
        if (path.isEmpty()) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
