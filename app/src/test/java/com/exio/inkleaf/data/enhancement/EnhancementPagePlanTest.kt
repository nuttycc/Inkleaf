package com.exio.inkleaf.data.enhancement

import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.PagePixelSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementPagePlanTest {
    @Test
    fun regionVolumeCarriesTheValidatedBudgetsIntoStripExecution() = runBlocking {
        val plan = planEnhancementPage(
            volume = planningVolume(),
            page = 0,
            scale = 2,
            maxInputPixels = 1_000_000L,
            maxOutputBytes = 48_000_000L,
        )

        assertEquals(
            EnhancementPagePlan.EnhanceStrips(
                sourceSize = PagePixelSize(2000, 3000),
                maxInputPixels = 1_000_000L,
                maxOutputBytes = 48_000_000L,
            ),
            plan,
        )
    }

    @Test
    fun regionVolumeSkipsWhenOneSourceRowExceedsTheInputBudget() = runBlocking {
        val plan = planEnhancementPage(
            volume = planningVolume(),
            page = 0,
            scale = 2,
            maxInputPixels = 1_999L,
            maxOutputBytes = 48_000_000L,
        )

        assertEquals(
            EnhancementPagePlan.Skip(EnhancementSkipReason.STRIP_MEMORY_BUDGET),
            plan,
        )
    }

    @Test
    fun regionVolumeSkipsWhenComposedOutputExceedsTheResidualBudget() = runBlocking {
        val plan = planEnhancementPage(
            volume = planningVolume(),
            page = 0,
            scale = 2,
            maxInputPixels = 1_000_000L,
            maxOutputBytes = 47_999_999L,
        )

        assertEquals(
            EnhancementPagePlan.Skip(EnhancementSkipReason.STRIP_MEMORY_BUDGET),
            plan,
        )
    }

    @Test
    fun pdfLikeVolumeCanUseStripsEvenThoughItsFastPathIsUnsupported() = runBlocking {
        val plan = planEnhancementPage(
            volume = planningVolume(supportsFastPath = false),
            page = 0,
            scale = 2,
            maxInputPixels = 1_000_000L,
            maxOutputBytes = 48_000_000L,
        )

        assertTrue(plan is EnhancementPagePlan.EnhanceStrips)
    }

    private fun planningVolume(
        supportsFastPath: Boolean = true,
    ): ComicVolume = object : ComicVolume {
        override val totalPageCount: Int = 1
        override val sourceRevision: String = "source"
        override val chapterCount: Int = 1
        override val supportsFastRasterEnhancement: Boolean = supportsFastPath
        override val supportsPageRegionLoad: Boolean = true
        override val fastRasterEnhancementSkipReason: EnhancementSkipReason =
            EnhancementSkipReason.PDF_UNSUPPORTED

        override fun chapterTitle(chapterIndex: Int): String = "chapter"

        override fun chapterStartPage(chapterIndex: Int): Int = 0

        override fun chapterPageCount(chapterIndex: Int): Int = 1

        override fun globalToChapterPage(globalPage: Int): ChapterProgress =
            ChapterProgress(chapterIndex = 0, pageIndex = 0)

        override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int = 0

        override suspend fun loadPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override suspend fun loadPageRasterSize(globalPage: Int): PagePixelSize? =
            PagePixelSize(width = 2000, height = 3000)

        override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override fun close() = Unit
    }
}
