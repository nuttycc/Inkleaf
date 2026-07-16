package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            assertEquals(model.downloadSize, model.artifacts.sumOf { it.bytes })
            assertEquals(model.artifacts.size, model.artifacts.map { it.filename }.toSet().size)
            model.artifacts.forEach { artifact ->
                assertTrue(artifact.bytes > 0)
                assertTrue(artifact.url.startsWith("https://"))
                assertTrue("Catalog URLs must be immutable", "/master/" !in artifact.url)
                assertTrue(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(artifact.filename.matches(Regex("[A-Za-z0-9._-]+")))
            }
        }
    }

    @Test
    fun initialCatalogContainsOnlyVerifiedModels() {
        assertEquals(
            listOf(
                "realcugan-2x-nose",
                "realcugan-2x-conservative",
                "waifu2x-upconv7-anime-2x",
            ),
            EnhancementModelCatalog.models.map { it.id },
        )
        assertEquals(2_554_717, EnhancementModelCatalog.require("realcugan-2x-nose").downloadSize)
        assertEquals(
            "2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a",
            EnhancementModelCatalog.require("realcugan-2x-nose").artifacts.first().sha256,
        )
    }

    @Test
    fun builtInAndCatalogSelectionsAreValid() {
        assertTrue(EnhancementSelectionIds.isValid(EnhancementSelectionIds.ORIGINAL))
        assertTrue(EnhancementSelectionIds.isValid(EnhancementSelectionIds.QUICK_CLARITY))
        EnhancementModelCatalog.models.forEach { model ->
            assertTrue(EnhancementSelectionIds.isValid(model.id))
        }
    }
}
