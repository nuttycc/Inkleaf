package com.exio.inkleaf.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateChannelDetectionTest {
    @Test
    fun `preview version name maps to the preview channel`() {
        assertEquals(
            AppUpdateChannel.PREVIEW,
            AppUpdateChecker.detectChannel("0.1.114-preview.20261013.87.1-1d1e7cc2"),
        )
    }

    @Test
    fun `stable version name maps to the stable channel`() {
        assertEquals(AppUpdateChannel.STABLE, AppUpdateChecker.detectChannel("0.1.114"))
    }

    @Test
    fun `missing version name falls back to the stable channel`() {
        assertEquals(AppUpdateChannel.STABLE, AppUpdateChecker.detectChannel(null))
    }
}
