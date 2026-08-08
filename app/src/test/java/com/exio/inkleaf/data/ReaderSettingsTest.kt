package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun `missing values use reader defaults`() {
        assertEquals(
            ReaderSettings(),
            readerSettingsFromStoredValues(
                pageDirection = null,
                stageBackground = null,
                pageStatusPosition = null,
                pageStatusColor = null,
            ),
        )
    }

    @Test
    fun `stored enum names restore all settings`() {
        assertEquals(
            ReaderSettings(
                pageDirection = ReaderPageDirection.RIGHT_TO_LEFT,
                stageBackground = ReaderStageBackground.BEIGE,
                pageStatusPosition = ReaderPageStatusPosition.END,
                pageStatusColor = ReaderPageStatusColor.BLACK,
            ),
            readerSettingsFromStoredValues(
                pageDirection = ReaderPageDirection.RIGHT_TO_LEFT.name,
                stageBackground = ReaderStageBackground.BEIGE.name,
                pageStatusPosition = ReaderPageStatusPosition.END.name,
                pageStatusColor = ReaderPageStatusColor.BLACK.name,
            ),
        )
    }

    @Test
    fun `unknown stored values fall back independently`() {
        assertEquals(
            ReaderSettings(
                pageDirection = ReaderPageDirection.LEFT_TO_RIGHT,
                stageBackground = ReaderStageBackground.DARK_GRAY,
                pageStatusPosition = ReaderPageStatusPosition.CENTER,
                pageStatusColor = ReaderPageStatusColor.AUTO,
            ),
            readerSettingsFromStoredValues(
                pageDirection = "removed_direction",
                stageBackground = ReaderStageBackground.DARK_GRAY.name,
                pageStatusPosition = "removed_position",
                pageStatusColor = "removed_color",
            ),
        )
    }
}
