package com.exio.inkleaf.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.R

@Composable
internal fun ReaderToolsPanelContent(
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onSetCover: (() -> Unit)?,
    onSaveToGallery: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        ReaderAttachedPanelHeader(title = ReaderPanel.Tools.title())
        ReaderAttachedPanelDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // 2x2 Expressive Control Grid
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            ReaderToolCard(
                label = if (isFavorite) "已收藏图片" else "收藏图片",
                subtitle = if (isFavorite) "取消收藏" else "存入全书收藏集",
                icon = if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                isActive = isFavorite,
                enabled = onToggleFavorite != null,
                onClick = { onToggleFavorite?.invoke() },
                modifier = Modifier.weight(1f),
            )
            ReaderToolCard(
                label = "保存到相册",
                subtitle = "存入系统相册",
                icon = R.drawable.ic_download,
                enabled = onSaveToGallery != null,
                onClick = { onSaveToGallery?.invoke() },
                modifier = Modifier.weight(1f),
            )
        }

        if (onSetCover != null) {
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                ReaderToolCard(
                    label = "设为封面",
                    subtitle = "设置为漫画书缩略图",
                    icon = R.drawable.ic_image,
                    onClick = onSetCover,
                    modifier = Modifier.weight(1f),
                )
                // Balance the 2-column grid so the cover card stays at half width
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReaderToolCard(
    label: String,
    subtitle: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false,
) {
    val containerColor =
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
            isActive -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }

    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            isActive -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }

    val iconTint =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.primary
        }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (isActive) 4.dp else 1.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
