package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class EnhancedImageDiskCacheEntry(
    val comicId: Long,
    val modelId: String,
    val sourceRevision: String,
    val cacheKey: String,
)

internal interface EnhancedImageDiskCacheCodec<T> {
    fun decode(file: File): T?

    fun encode(value: T, output: OutputStream): Boolean

    fun isValid(file: File): Boolean = file.length() > 0L && decode(file) != null
}

internal class EnhancedImageDiskCacheDecodeUnavailableException(cause: Throwable) :
    RuntimeException(cause)

/**
 * Owns the file layout and transactional operations without depending on Android graphics APIs.
 * Keeping this layer platform-neutral makes cache recovery and path behavior JVM-testable.
 */
internal class EnhancedImageDiskCacheStore<T>(
    cacheDir: File,
    filesDir: File,
    private val codec: EnhancedImageDiskCacheCodec<T>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val transientRoot = File(cacheDir, CACHE_DIRECTORY)
    private val pinnedRoot = File(filesDir, CACHE_DIRECTORY)

    suspend fun read(entry: EnhancedImageDiskCacheEntry): T? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            fileFor(pinnedRoot, entry),
            fileFor(transientRoot, entry),
        )
        for (file in candidates) {
            if (!file.isFile) continue
            val decoded = try {
                codec.decode(file)
            } catch (_: EnhancedImageDiskCacheDecodeUnavailableException) {
                return@withContext null
            } catch (_: Exception) {
                null
            }
            if (decoded == null) {
                file.delete()
                continue
            }
            file.setLastModified(now())
            return@withContext decoded
        }
        null
    }

    suspend fun writeTransient(entry: EnhancedImageDiskCacheEntry, value: T): Boolean =
        write(transientRoot, entry, value)

    suspend fun writePinned(entry: EnhancedImageDiskCacheEntry, value: T): Boolean =
        write(pinnedRoot, entry, value).also { written ->
            if (written) {
                withContext(Dispatchers.IO) {
                    fileFor(transientRoot, entry).delete()
                }
            }
        }

    suspend fun containsPinned(entry: EnhancedImageDiskCacheEntry): Boolean =
        withContext(Dispatchers.IO) {
            val file = fileFor(pinnedRoot, entry)
            if (!file.isFile) return@withContext false
            val valid = try {
                codec.isValid(file)
            } catch (_: Exception) {
                false
            }
            if (valid) {
                file.setLastModified(now())
            } else {
                file.delete()
            }
            valid
        }

    suspend fun promoteToPinned(entry: EnhancedImageDiskCacheEntry): Boolean =
        withContext(Dispatchers.IO) {
            val source = fileFor(transientRoot, entry)
            val target = fileFor(pinnedRoot, entry)
            if (!source.isFile || source.length() <= 0L) {
                val validTarget = try {
                    target.isFile && codec.isValid(target)
                } catch (_: Exception) {
                    false
                }
                if (validTarget) {
                    target.setLastModified(now())
                } else {
                    target.delete()
                }
                return@withContext validTarget
            }
            val validSource = try {
                codec.isValid(source)
            } catch (_: Exception) {
                false
            }
            if (!validSource) {
                source.delete()
                return@withContext false
            }
            val directory = target.parentFile ?: return@withContext false
            if (!directory.exists() && !directory.mkdirs()) return@withContext false

            val temporary = temporaryFileFor(target)
            try {
                copyAndSync(source, temporary)
                moveAtomically(temporary, target)
                target.setLastModified(now())
                source.delete()
                true
            } catch (_: Exception) {
                false
            } finally {
                temporary.delete()
            }
        }

    suspend fun enforceTransientBudget(maxBytes: Long) = withContext(Dispatchers.IO) {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        val files = transientRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == PNG_EXTENSION }
            .toList()
        var totalBytes = files.sumOf(File::length)
        if (totalBytes <= maxBytes) return@withContext

        for (file in files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })) {
            if (totalBytes <= maxBytes) break
            val bytes = file.length()
            if (file.delete()) totalBytes -= bytes
        }
    }

    suspend fun deleteComic(comicId: Long) = withContext(Dispatchers.IO) {
        comicDirectory(transientRoot, comicId).deleteRecursively()
        comicDirectory(pinnedRoot, comicId).deleteRecursively()
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        requireSafeSegment(modelId, "modelId")
        deleteModelFromRoot(transientRoot, modelId)
        deleteModelFromRoot(pinnedRoot, modelId)
    }

    internal fun transientFile(entry: EnhancedImageDiskCacheEntry): File =
        fileFor(transientRoot, entry)

    internal fun pinnedFile(entry: EnhancedImageDiskCacheEntry): File =
        fileFor(pinnedRoot, entry)

    /** Root used for pinned cache volume queries (total space). */
    internal fun pinnedStorageRoot(): File =
        if (pinnedRoot.exists()) pinnedRoot else pinnedRoot.parentFile ?: pinnedRoot

    /** Root used for transient cache volume queries. */
    internal fun transientStorageRoot(): File =
        if (transientRoot.exists()) transientRoot else transientRoot.parentFile ?: transientRoot

    private suspend fun write(
        root: File,
        entry: EnhancedImageDiskCacheEntry,
        value: T,
    ): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(root, entry)
        val directory = target.parentFile ?: return@withContext false
        if (!directory.exists() && !directory.mkdirs()) return@withContext false

        val temporary = temporaryFileFor(target)
        try {
            val encoded = FileOutputStream(temporary).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { bufferedOutput ->
                    val success = codec.encode(value, bufferedOutput)
                    bufferedOutput.flush()
                    fileOutput.fd.sync()
                    success
                }
            }
            if (!encoded || temporary.length() <= 0L) return@withContext false
            moveAtomically(temporary, target)
            target.setLastModified(now())
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    private fun fileFor(root: File, entry: EnhancedImageDiskCacheEntry): File {
        require(entry.comicId >= 0L) { "comicId must be non-negative" }
        requireSafeSegment(entry.modelId, "modelId")
        requireSafeSegment(entry.sourceRevision, "sourceRevision")
        return File(
            File(
                File(
                    comicDirectory(root, entry.comicId),
                    entry.modelId,
                ),
                entry.sourceRevision,
            ),
            "${sha256Hex(entry.cacheKey)}.png",
        )
    }

    private fun comicDirectory(root: File, comicId: Long): File {
        require(comicId >= 0L) { "comicId must be non-negative" }
        return File(File(root, FORMAT_VERSION), comicId.toString())
    }

    private fun deleteModelFromRoot(root: File, modelId: String) {
        val versionDirectory = File(root, FORMAT_VERSION)
        versionDirectory.listFiles()
            ?.filter(File::isDirectory)
            ?.forEach { comicDirectory ->
                File(comicDirectory, modelId).deleteRecursively()
                if (comicDirectory.listFiles().isNullOrEmpty()) comicDirectory.delete()
            }
    }

    private fun temporaryFileFor(target: File): File =
        File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")

    private fun copyAndSync(source: File, target: File) {
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    input.copyTo(output)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        }
    }

    private fun moveAtomically(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun requireSafeSegment(value: String, name: String) {
        require(
            value.isNotBlank() &&
                    value != "." &&
                    value != ".." &&
                    SAFE_SEGMENT.matches(value)
        ) {
            "$name contains unsupported path characters"
        }
    }

    private fun sha256Hex(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toLowerHex()
    }

    private companion object {
        const val CACHE_DIRECTORY = "ai_enhanced_images"
        const val FORMAT_VERSION = "v1"
        const val PNG_EXTENSION = "png"
        val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
    }
}
