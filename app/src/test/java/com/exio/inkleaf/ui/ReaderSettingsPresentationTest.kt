package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ReaderPageDirection
import com.exio.inkleaf.data.ReaderPageStatusColor
import com.exio.inkleaf.data.ReaderPageStatusPosition
import com.exio.inkleaf.data.ReaderSettings
import com.exio.inkleaf.data.ReaderStageBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsPresentationTest {
    @Test
    fun `tap zones follow left to right direction`() {
        assertEquals(
            ReaderTransitionDirection.PREVIOUS,
            readerTapTurnDirection(ReaderPageDirection.LEFT_TO_RIGHT, isLeftZone = true),
        )
        assertEquals(
            ReaderTransitionDirection.NEXT,
            readerTapTurnDirection(ReaderPageDirection.LEFT_TO_RIGHT, isLeftZone = false),
        )
    }

    @Test
    fun `tap zones reverse for right to left direction`() {
        assertEquals(
            ReaderTransitionDirection.NEXT,
            readerTapTurnDirection(ReaderPageDirection.RIGHT_TO_LEFT, isLeftZone = true),
        )
        assertEquals(
            ReaderTransitionDirection.PREVIOUS,
            readerTapTurnDirection(ReaderPageDirection.RIGHT_TO_LEFT, isLeftZone = false),
        )
    }

    @Test
    fun `only right to left reverses pager layout`() {
        assertFalse(readerPagerReverseLayout(ReaderPageDirection.LEFT_TO_RIGHT))
        assertTrue(readerPagerReverseLayout(ReaderPageDirection.RIGHT_TO_LEFT))
    }

    @Test
    fun `page status positions map to bottom alignments`() {
        assertEquals(
            androidx.compose.ui.Alignment.BottomStart,
            readerPageStatusAlignment(ReaderPageStatusPosition.START),
        )
        assertEquals(
            androidx.compose.ui.Alignment.BottomCenter,
            readerPageStatusAlignment(ReaderPageStatusPosition.CENTER),
        )
        assertEquals(
            androidx.compose.ui.Alignment.BottomEnd,
            readerPageStatusAlignment(ReaderPageStatusPosition.END),
        )
    }

    @Test
    fun `auto page status tone chooses dark content only for beige`() {
        assertEquals(
            ReaderPageStatusTone.LIGHT_CONTENT,
            readerPageStatusTone(
                ReaderSettings(stageBackground = ReaderStageBackground.BLACK)
            ),
        )
        assertEquals(
            ReaderPageStatusTone.LIGHT_CONTENT,
            readerPageStatusTone(
                ReaderSettings(stageBackground = ReaderStageBackground.DARK_GRAY)
            ),
        )
        assertEquals(
            ReaderPageStatusTone.DARK_CONTENT,
            readerPageStatusTone(
                ReaderSettings(stageBackground = ReaderStageBackground.BEIGE)
            ),
        )
    }

    @Test
    fun `explicit page status color overrides auto`() {
        assertEquals(
            ReaderPageStatusTone.LIGHT_CONTENT,
            readerPageStatusTone(
                ReaderSettings(
                    stageBackground = ReaderStageBackground.BEIGE,
                    pageStatusColor = ReaderPageStatusColor.WHITE,
                )
            ),
        )
        assertEquals(
            ReaderPageStatusTone.DARK_CONTENT,
            readerPageStatusTone(
                ReaderSettings(
                    stageBackground = ReaderStageBackground.BLACK,
                    pageStatusColor = ReaderPageStatusColor.BLACK,
                )
            ),
        )
    }
}
