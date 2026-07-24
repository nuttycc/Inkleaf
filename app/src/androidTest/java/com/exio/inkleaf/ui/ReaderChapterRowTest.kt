package com.exio.inkleaf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderChapterRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readableChapterDispatchesPhysicalTap() {
        var selectedPage = -1
        composeRule.setContent {
            MaterialTheme {
                ReaderChapterRow(
                    chapter = ReaderChapterItem(
                        index = 2,
                        title = "第三章",
                        pageCount = 18,
                        startPage = 42,
                        isReadable = true,
                    ),
                    onSelect = { selectedPage = it },
                    modifier = Modifier.testTag("readable-chapter"),
                )
            }
        }

        composeRule.onNodeWithTag("readable-chapter")
            .assertIsEnabled()
            .performTouchInput { click() }

        composeRule.runOnIdle { assertEquals(42, selectedPage) }
    }

    @Test
    fun currentReadableChapterExposesSelectedStateDescription() {
        composeRule.setContent {
            MaterialTheme {
                ReaderChapterRow(
                    chapter = ReaderChapterItem(
                        index = 1,
                        title = "第二章",
                        pageCount = 18,
                        startPage = 18,
                        isReadable = true,
                    ),
                    isCurrent = true,
                    onSelect = {},
                    modifier = Modifier.testTag("current-chapter"),
                )
            }
        }

        composeRule.onNodeWithTag("current-chapter")
            .assertIsSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "当前章节",
                ),
            )
    }

    @Test
    fun unreadableChapterIsDisabledAndIgnoresPhysicalTap() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                ReaderChapterRow(
                    chapter = ReaderChapterItem(
                        index = 3,
                        title = "损坏章节",
                        pageCount = 0,
                        startPage = 42,
                        isReadable = false,
                    ),
                    onSelect = { clicks++ },
                    modifier = Modifier.testTag("unreadable-chapter"),
                )
            }
        }

        composeRule.onNodeWithTag("unreadable-chapter")
            .assertIsNotEnabled()
            .assertIsNotSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "无法打开",
                ),
            )
            .performTouchInput { click() }

        composeRule.runOnIdle { assertEquals(0, clicks) }
    }
}
