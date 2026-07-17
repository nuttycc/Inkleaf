package com.exio.inkleaf.data.enhancement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnhancementModelCatalogTest {
    @Test
    fun catalogIdsAreUniqueStableAndFilenameSafe() {
        val ids = EnhancementModelCatalog.models.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
            assertNotNull(EnhancementModelCatalog.find(id))
        }
        assertTrue(ids.none { it in EnhancementSelectionIds.builtIn })
    }

    @Test
    fun descriptorSizesMatchArtifactsAndHashesAreSha256() {
        EnhancementModelCatalog.models.forEach { model ->
            assertTrue(model.artifacts.isNotEmpty())
            assertEquals(model.installedSize, model.artifacts.sumOf { it.bytes })
            assertEquals(model.installedSize, model.downloadSize)
            assertEquals(model.artifacts.size, model.artifacts.map { it.filename }.toSet().size)
            model.artifacts.forEach { artifact ->
                assertTrue(artifact.bytes > 0)
                assertTrue(artifact.url.startsWith("https://"))
                assertTrue(
                    "Catalog URLs must not track an upstream master branch",
                    "/master/" !in artifact.url
                )
                assertTrue(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(artifact.filename.matches(Regex("[A-Za-z0-9._-]+")))
            }
        }
    }

    @Test
    fun realEsrganCatalogMatchesPublishedManifest() {
        val manifest = Json.parseToJsonElement(distributionManifest().readText()).jsonObject
        val entries = manifest.getValue("files").jsonArray.map { element ->
            val entry = element.jsonObject
            entry.getValue("path").jsonPrimitive.content to entry
        }
        assertEquals(entries.size, entries.map { it.first }.toSet().size)
        val files = entries.toMap()
        val catalogPaths = mutableSetOf<String>()

        EnhancementModelCatalog.models.filter { it.family == "Real-ESRGAN" }.forEach { model ->
            model.artifacts.forEach { artifact ->
                val path = artifact.url.substringAfter("/v0.2.5.0/")
                catalogPaths += path
                val manifestEntry = files.getValue(path)
                assertEquals(
                    artifact.bytes,
                    manifestEntry.getValue("bytes").jsonPrimitive.content.toLong()
                )
                assertEquals(
                    artifact.sha256,
                    manifestEntry.getValue("sha256").jsonPrimitive.content
                )
            }
        }
        val intentionallyUnsupported = setOf(
            "animevideov3-x3/realesr-animevideov3-x3.bin",
            "animevideov3-x3/realesr-animevideov3-x3.param",
        )
        assertEquals(files.keys - intentionallyUnsupported, catalogPaths)
    }

    @Test
    fun initialCatalogContainsOnlyVerifiedModels() {
        assertEquals(
            listOf(
                "realcugan-2x-nose",
                "realcugan-2x-conservative",
                "waifu2x-upconv7-anime-2x",
                "realesrgan-animevideov3-2x",
                "realesrgan-animevideov3-4x",
                "realesrgan-x4plus-anime-4x",
                "realesrgan-x4plus-4x",
            ),
            EnhancementModelCatalog.models.map { it.id },
        )
        assertEquals(2_554_717, EnhancementModelCatalog.require("realcugan-2x-nose").downloadSize)
        assertEquals(
            "2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a",
            EnhancementModelCatalog.require("realcugan-2x-nose").artifacts.first().sha256,
        )
        assertEquals(
            1_250_541L,
            EnhancementModelCatalog.require("realesrgan-animevideov3-2x").downloadSize,
        )
        assertEquals(
            1_250_445L,
            EnhancementModelCatalog.require("realesrgan-animevideov3-4x").downloadSize,
        )
        assertEquals(
            8_973_790L,
            EnhancementModelCatalog.require("realesrgan-x4plus-anime-4x").installedSize,
        )
        assertEquals(
            33_540_549L,
            EnhancementModelCatalog.require("realesrgan-x4plus-4x").installedSize,
        )
        EnhancementModelCatalog.models.filter { it.family == "Real-ESRGAN" }.forEach { model ->
            model.artifacts.forEach { artifact ->
                assertTrue(artifact.url.startsWith("https://huggingface.co/vozzy/inkleaf-models/"))
                assertTrue("/v0.2.5.0/" in artifact.url)
            }
        }
    }

    @Test
    fun builtInAndCatalogSelectionsAreValid() {
        assertTrue(EnhancementSelectionIds.isValid(EnhancementSelectionIds.ORIGINAL))
        assertTrue(EnhancementSelectionIds.isValid(EnhancementSelectionIds.QUICK_CLARITY))
        EnhancementModelCatalog.models.forEach { model ->
            assertTrue(EnhancementSelectionIds.isValid(model.id))
        }
    }

    private fun distributionManifest(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = directory.resolve(
                "model-distribution/realesrgan-ncnn-vulkan-v0.2.5.0-manifest.json"
            )
            if (candidate.isFile) return candidate
            directory = directory.parentFile
                ?: error("Could not locate the Real-ESRGAN distribution manifest")
        }
    }
}
