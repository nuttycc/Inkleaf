package com.exio.comicreader.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable

/**
 * 本 App 所有 ModalBottomSheet 共用的状态：enabledValues 不含
 * PartiallyExpanded（= 旧 API 的 skipPartiallyExpanded = true）。
 * 这些 sheet 内容都不高，半展开态没有意义，一步到全展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberExpandOnlySheetState() = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
)
