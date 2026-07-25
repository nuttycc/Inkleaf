package com.exio.inkleaf.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPageRenderRequestTest {
    @Test
    fun targetedVolumesGetFallbackRequestOnceViewportIsKnown() {
        assertNotNull(
            targetedPageRenderRequest(
                supportsTargetedPageBitmap = true,
                viewportSize = IntSize(1080, 1920),
                zoomed = false,
            )
        )
    }

    @Test
    fun zoomedVolumesStillGetTargetedRenderRequest() {
        val request =
            targetedPageRenderRequest(
                supportsTargetedPageBitmap = true,
                viewportSize = IntSize(1080, 1920),
                zoomed = true,
            )
        assertNotNull(request)
    }

    @Test
    fun unsupportedOrUnmeasuredVolumesHaveNoRequest() {
        assertNull(
            targetedPageRenderRequest(
                supportsTargetedPageBitmap = false,
                viewportSize = IntSize(1080, 1920),
                zoomed = false,
            )
        )
        assertNull(
            targetedPageRenderRequest(
                supportsTargetedPageBitmap = true,
                viewportSize = IntSize.Zero,
                zoomed = false,
            )
        )
        assertNull(
            targetedPageRenderRequest(
                supportsTargetedPageBitmap = true,
                viewportSize = IntSize(0, 1920),
                zoomed = false,
            )
        )
    }
}
