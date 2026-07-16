package com.exio.inkleaf.data.enhancement

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

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

    fun extractModelArchive(
        archiveFile: File,
        destination: File,
        model: EnhancementModelDescriptor,
    ) {
        requireNotNull(model.archive) { "Model does not declare an archive source" }
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("无法创建模型解压目录")
        }
        val destinationRoot = destination.canonicalFile
        ZipFile(archiveFile).use { archive ->
            model.artifacts.forEach { artifact ->
                val archiveEntry = requireNotNull(artifact.archiveEntry) {
                    "Archived artifact is missing archiveEntry: ${artifact.filename}"
                }
                val matches = archive.entries().asSequence()
                    .filter { entry -> entry.name == archiveEntry }
                    .toList()
                if (matches.size != 1 || matches.single().isDirectory) {
                    throw IOException("模型压缩包缺少唯一文件：$archiveEntry")
                }
                val entry = matches.single()
                if (entry.size >= 0L && entry.size != artifact.bytes) {
                    throw IOException("模型压缩包内文件大小不匹配：$archiveEntry")
                }

                val target = File(destinationRoot, artifact.filename).canonicalFile
                if (target.parentFile != destinationRoot) {
                    throw IOException("模型目标文件名不安全：${artifact.filename}")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var extractedBytes = 0L
                archive.getInputStream(entry).use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            extractedBytes += read
                            if (extractedBytes > artifact.bytes) {
                                throw IOException("模型压缩包内文件超出预期大小：$archiveEntry")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                if (extractedBytes != artifact.bytes) {
                    throw IOException("模型压缩包内文件不完整：$archiveEntry")
                }
                if (!digest.digest().toLowerHex().equals(artifact.sha256, ignoreCase = true)) {
                    throw IOException("模型压缩包内文件 SHA-256 校验失败：$archiveEntry")
                }
            }
        }
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
