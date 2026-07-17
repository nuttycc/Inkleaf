package com.exio.inkleaf.data.enhancement

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object EnhancementModelFiles {
    fun isArtifactValid(file: File, artifact: EnhancementModelArtifact): Boolean =
        file.isFile && file.length() == artifact.bytes && sha256(file) == artifact.sha256

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    fun isModelInstalled(directory: File, model: EnhancementModelDescriptor): Boolean =
        directory.isDirectory && model.artifacts.all { artifact ->
            isArtifactValid(File(directory, artifact.filename), artifact)
        }

    fun recoverInstalledModel(
        rootDirectory: File,
        model: EnhancementModelDescriptor,
        atomicMove: (File, File) -> Unit,
    ): Boolean {
        val target = File(rootDirectory, model.id)
        val backups = rootDirectory.listFiles()
            ?.filter { file ->
                file.name.startsWith(".${model.id}.") && file.name.endsWith(".backup")
            }
            .orEmpty()

        if (isModelInstalled(target, model)) {
            backups.forEach(File::deleteRecursively)
            return true
        }

        val validBackup = backups
            .filter { isModelInstalled(it, model) }
            .maxByOrNull(File::lastModified)
            ?: run {
                backups.forEach(File::deleteRecursively)
                return false
            }

        if (target.exists() && !target.deleteRecursively()) return false
        return runCatching {
            atomicMove(validBackup, target)
            backups.filterNot { it == validBackup }.forEach(File::deleteRecursively)
            true
        }.getOrDefault(false)
    }
}

internal fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
