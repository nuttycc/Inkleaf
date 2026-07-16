package com.exio.inkleaf.data.enhancement

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NcnnEnhancementEngineTest {
    @Test
    fun waifu2xCpuInferenceProducesDoubleSizedBitmap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelId = "waifu2x-upconv7-anime-2x"
        assumeTrue(
            "Push the verified model package into app-private storage to run this integration test.",
            EnhancementModelRepository.getInstance(context).installedDirectory(modelId) != null,
        )

        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x / 4 + y / 4) % 2 == 0) Color.BLACK else Color.WHITE
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

        val outcome = NcnnEnhancementEngine.enhance(
            context = context,
            modelId = modelId,
            source = source,
            cacheKey = "instrumentation-waifu2x-cpu",
            preferVulkan = false,
        )

        assertTrue(outcome is EnhancementInferenceOutcome.Success)
        val success = outcome as EnhancementInferenceOutcome.Success
        assertEquals(EnhancementInferenceBackend.CPU, success.backend)
        assertEquals(64, success.bitmap.width)
        assertEquals(64, success.bitmap.height)
    }
}
