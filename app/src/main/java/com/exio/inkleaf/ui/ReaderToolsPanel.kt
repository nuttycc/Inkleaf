package com.exio.inkleaf.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import com.exio.inkleaf.R

@Composable
internal fun ReaderToolsPanelContent(
    isFavorite: Boolean,
    ocrBusy: Boolean,
    onToggleFavorite: () -> Unit,
    onRecognizePage: () -> Unit,
    onSetCover: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        ReaderAttachedPanelHeader(
            title = ReaderPanel.Tools.title(),
        )
        ReaderToolRow(
            label = if (isFavorite) "取消收藏当前页图片" else "收藏当前页图片",
            icon = if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
            onClick = {
                onToggleFavorite()
            },
        )
        ReaderToolRow(
            label = if (ocrBusy) "正在识别当前页文字…" else "识别当前页文字",
            icon = MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_document_scanner_outlined,
            enabled = !ocrBusy,
            onClick = {
                onRecognizePage()
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ReaderToolRow(
            label = "设为封面",
            icon = R.drawable.ic_image,
            onClick = {
                onSetCover()
            },
        )
    }
}

@Composable
private fun ReaderToolRow(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}
