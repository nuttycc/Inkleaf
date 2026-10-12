package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PageImageTransformsTest {
    private val transform =
        PageImageTransform(type = PageImageTransformType.JM_SEGMENTATION, scrambleId = 3)

    /** Rejects chapter identifiers that cannot supply the numeric input required by the transform. */
    @Test
    fun `validate rejects a non numeric chapter id`() {
        assertThrows(PluginContentValidationException::class.java) {
            PageImageTransforms.validate(transform, "abc")
        }
    }

    /** Covers the lower bound on chapter numbers accepted by transform validation. */
    @Test
    fun `validate rejects a negative chapter id`() {
        assertThrows(PluginContentValidationException::class.java) {
            PageImageTransforms.validate(transform, "-1")
        }
    }

    /** Covers the lower bound on source-declared scramble identifiers. */
    @Test
    fun `validate rejects a negative scramble id`() {
        assertThrows(PluginContentValidationException::class.java) {
            PageImageTransforms.validate(transform.copy(scrambleId = -1), "100")
        }
    }

    /** Checks that a supported transform with valid numeric inputs passes validation. */
    @Test
    fun `validate accepts a non negative numeric chapter id`() {
        PageImageTransforms.validate(transform, "100")
    }

    /** Preserves the existing page identity when no image transform is declared. */
    @Test
    fun `identity suffix is empty without a transform`() {
        assertEquals("", PageImageTransforms.identitySuffix(null))
    }

    /** Requires transform type and scramble ID to participate in cached page identity. */
    @Test
    fun `identity suffix names the transform and its scramble id`() {
        assertEquals(":transform=JM_SEGMENTATION,3", PageImageTransforms.identitySuffix(transform))
    }

    /** Checks that an untransformed page returns the original byte array without copying. */
    @Test
    fun `apply returns the same bytes when the page declares no transform`() {
        val bytes = byteArrayOf(1, 2, 3)
        val page = descriptor(imageTransform = null)
        assertSame(bytes, PageImageTransforms.apply(bytes, "100", page))
    }

    /**
     * Requires invalid chapter metadata that bypassed validation to fail before scrambled bytes
     * reach the reader.
     */
    @Test
    fun `apply fails rather than passing scrambled bytes through for a non numeric chapter id`() {
        val page = descriptor(imageTransform = transform)
        assertThrows(ComicOpenException::class.java) {
            PageImageTransforms.apply(byteArrayOf(1, 2, 3), "abc", page)
        }
    }

    /** Creates a fixed page descriptor with an optional transform for byte-application tests. */
    private fun descriptor(imageTransform: PageImageTransform?) =
        PageDescriptor(
            index = 0,
            url = "https://example.test/1.jpg",
            imageTransform = imageTransform,
        )
}
