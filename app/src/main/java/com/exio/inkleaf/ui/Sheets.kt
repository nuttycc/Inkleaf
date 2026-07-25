package com.exio.inkleaf.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 本 App 所有 ModalBottomSheet 共用的状态：enabledValues 不含 PartiallyExpanded（= 旧 API 的
 * skipPartiallyExpanded = true）。 这些 sheet 内容都不高，半展开态没有意义，一步到全展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberExpandOnlySheetState() =
    rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

/** ModalBottomSheet 标题：对齐 Material3 ListItem 的 16dp 内容起始线。 */
@Composable
internal fun StandardSheetTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * sheet 内容列的统一骨架：铺满宽度 + 导航栏避让 + 底部呼吸留白。 把 navigationBarsPadding 的约定收在一处，新增 sheet 不会漏掉 而让内容被手势条遮住。
 */
@Composable
internal fun SheetColumn(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    scrollState: ScrollState? = null,
    selectable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .then(
                    if (scrollable) {
                        Modifier.verticalScroll(scrollState ?: rememberScrollState())
                    } else {
                        Modifier
                    }
                )
                .then(if (selectable) Modifier.selectableGroup() else Modifier)
                .padding(bottom = 12.dp),
        content = content,
    )
}
