package com.exio.inkleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.exio.inkleaf.R
import com.exio.inkleaf.data.db.FavoritePageEntity
import java.io.File

@Composable
internal fun FavoritesContent(
    favorites: List<FavoritePageEntity>?,
    onlineFavorites: List<OnlineSavedFavoriteUi>?,
    onOpenFavorite: (Long) -> Unit,
    onOpenOnlineFavorite: (OnlineSavedFavoriteUi) -> Unit,
    onRemoveOnlineFavorite: (OnlineSavedFavoriteUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        favorites == null && onlineFavorites == null -> Box(modifier = modifier)

        favorites.orEmpty().isEmpty() && onlineFavorites.orEmpty().isEmpty() ->
            EmptyFavorites(modifier = modifier)

        else -> {
            val entries =
                remember(favorites, onlineFavorites) {
                    buildList {
                            favorites.orEmpty().forEach { add(FavoriteGridEntry.Local(it)) }
                            onlineFavorites.orEmpty().forEach { add(FavoriteGridEntry.Online(it)) }
                        }
                        .sortedByDescending(FavoriteGridEntry::addedAt)
                }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = GridDefaults.AdaptiveMinCellWidth),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier,
            ) {
                items(entries, key = FavoriteGridEntry::stableKey) { entry ->
                    when (entry) {
                        is FavoriteGridEntry.Local ->
                            FavoriteCard(
                                favorite = entry.favorite,
                                onClick = { onOpenFavorite(entry.favorite.id) },
                            )
                        is FavoriteGridEntry.Online ->
                            OnlineFavoriteCard(
                                item = entry.favorite,
                                onClick = { onOpenOnlineFavorite(entry.favorite) },
                                onRemove = { onRemoveOnlineFavorite(entry.favorite) },
                            )
                    }
                }
            }
        }
    }
}

private sealed interface FavoriteGridEntry {
    val stableKey: String
    val addedAt: Long

    data class Local(val favorite: FavoritePageEntity) : FavoriteGridEntry {
        override val stableKey: String = "local:${favorite.id}"
        override val addedAt: Long = favorite.addedAt
    }

    data class Online(val favorite: OnlineSavedFavoriteUi) : FavoriteGridEntry {
        override val stableKey: String = favorite.key
        override val addedAt: Long = favorite.addedAtMs
    }
}

@Composable
private fun EmptyFavorites(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_favorite_border),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "还没有收藏",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "阅读时在菜单里选择「收藏当前页图片」。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FavoriteCard(
    favorite: FavoritePageEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val imageFile =
                remember(favorite) {
                    favorite.thumbnailPath?.let(::File)?.takeIf { it.exists() }
                        ?: File(favorite.imagePath).takeIf { it.exists() }
                }
            if (imageFile != null) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = favorite.sourceTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = favorite.sourceTitle.take(1),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = favorite.sourceTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "第 ${favorite.pageIndex + 1} 页",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnlineFavoriteCard(
    item: OnlineSavedFavoriteUi,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val snapshot = remember(item.snapshotFile) { item.snapshotFile?.takeIf(File::isFile) }
            if (snapshot != null) {
                AsyncImage(
                    model = snapshot,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = item.title.take(1),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "收藏操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("取消收藏") },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "${item.chapterTitle} · 第 ${item.pageIndex + 1} 页",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!item.availability.canOpenReader()) {
            Text(
                text = item.availability.displayLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
