package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnhancementModelFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sha256MatchesKnownDigest() {
        val file = temporaryFolder.newFile("model.bin")
        file.writeText("inkleaf")

        assertEquals(
            "e874f3cbba79649757c606729d2035ee37784c9e14dc67b38b410f6f0df4092b",
            EnhancementModelFiles.sha256(file),
        )
    }

    @Test
    fun artifactValidationRequiresExactSizeAndHash() {
        val file = temporaryFolder.newFile("model.bin")
        file.writeText("abc")
        val valid = EnhancementModelArtifact(
            filename = file.name,
            url = "https://example.invalid/model.bin",
            bytes = 3,
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )

        assertTrue(EnhancementModelFiles.isArtifactValid(file, valid))
        assertFalse(EnhancementModelFiles.isArtifactValid(file, valid.copy(bytes = 4)))
        assertFalse(EnhancementModelFiles.isArtifactValid(file, valid.copy(sha256 = "0".repeat(64))))
    }

    @Test
    fun modelInstallRequiresEveryArtifact() {
        val directory = temporaryFolder.newFolder("model")
        val bin = directory.resolve("model.bin").apply { writeText("abc") }
        val param = directory.resolve("model.param").apply { writeText("def") }
        val model = testModel(
            artifacts = listOf(artifactFor(bin), artifactFor(param)),
        )

        assertTrue(EnhancementModelFiles.isModelInstalled(directory, model))
        param.delete()
        assertFalse(EnhancementModelFiles.isModelInstalled(directory, model))
    }

    @Test
    fun validBackupIsRecoveredWhenInstallCommitWasInterrupted() {
        val root = temporaryFolder.newFolder("models")
        val model = testModel()
        val backup = File(root, ".${model.id}.interrupted.backup").apply { mkdirs() }
        File(backup, model.artifacts.single().filename).writeText("abc")

        val recovered = EnhancementModelFiles.recoverInstalledModel(root, model) { source, target ->
            java.nio.file.Files.move(source.toPath(), target.toPath())
        }

        assertTrue(recovered)
        assertTrue(EnhancementModelFiles.isModelInstalled(File(root, model.id), model))
        assertFalse(backup.exists())
    }

    private fun testModel(
        artifacts: List<EnhancementModelArtifact> = listOf(
            EnhancementModelArtifact(
                filename = "model.bin",
                url = "https://example.invalid/model.bin",
                bytes = 3,
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
        ),
    ): EnhancementModelDescriptor {
        return EnhancementModelDescriptor(
            id = "test-model",
            displayName = "Test",
            family = "Test",
            version = "1",
            variant = "test",
            scale = 2,
            targetBackend = "test",
            capabilities = emptyList(),
            recommendedFor = emptyList(),
            license = "test",
            sourceUrl = "https://example.invalid",
            artifacts = artifacts,
        )
    }

    private fun artifactFor(file: File) = EnhancementModelArtifact(
        filename = file.name,
        url = "https://example.invalid/${file.name}",
        bytes = file.length(),
        sha256 = EnhancementModelFiles.sha256(file),
    )
}
