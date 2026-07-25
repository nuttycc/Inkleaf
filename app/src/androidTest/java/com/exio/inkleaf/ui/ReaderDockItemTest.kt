package com.exio.inkleaf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderDockItemTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pageItemExposesExpandedSelection() {
        composeRule.setContent {
            MaterialTheme {
                ReaderDockItem(
                    destination = ReaderDockDestination.Pages,
                    selected = true,
                    accent = Color.Cyan,
                    onClick = {},
                    modifier = Modifier.testTag("page-dock-item"),
                )
            }
        }

        composeRule.onNodeWithTag("page-dock-item").assertIsSelected()
    }

    @Test
    fun bookmarkItemDispatchesPhysicalTap() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                ReaderDockItem(
                    destination = ReaderDockDestination.Bookmarks,
                    selected = false,
                    accent = Color.Cyan,
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("bookmark-dock-item"),
                )
            }
        }

        composeRule.onNodeWithTag("bookmark-dock-item").assertIsNotSelected().performTouchInput {
            click()
        }

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
