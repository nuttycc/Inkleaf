package com.exio.comicreader.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.comicreader.data.CacheLimit
import com.exio.comicreader.data.DarkMode
import com.exio.comicreader.data.ThemeSeed

/** 设置页：主题（种子色卡 / 深浅模式 / 壁纸取色）+ 漫画库入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFolders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsageBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cacheBudgetBytes = remember(cacheLimit, context) { cacheLimit.bytes(context) }
    val autoCacheBudgetBytes = remember(context) { CacheLimit.AUTO.bytes(context) }
    var showCacheLimitSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("主题")

            // 种子色卡：点选即全 App 变色（与排版抽屉同款"实时预览"思路）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ThemeSeed.entries.forEach { seed ->
                    SeedSwatch(
                        seed = seed,
                        selected = !theme.useWallpaper && theme.seed == seed,
                        onClick = { viewModel.setSeed(seed) },
                    )
                }
            }

            // 壁纸取色：Material You 动态色仅 Android 12+ 提供
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text("壁纸取色") },
                    supportingContent = { Text("跟随系统壁纸生成配色（Material You）") },
                    trailingContent = {
                        Switch(
                            checked = theme.useWallpaper,
                            onCheckedChange = viewModel::setUseWallpaper,
                        )
                    },
                )
            }

            SectionLabel("深浅模式")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                DarkMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = theme.darkMode == mode,
                        onClick = { viewModel.setDarkMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                    ) {
                        Text(mode.label)
                    }
                }
            }

            SectionLabel("存储")
            ListItem(
                headlineContent = { Text("漫画缓存上限") },
                supportingContent = {
                    Text(
                        "当前占用 ${formatBytes(cacheUsage)} · " +
                            cacheLimitSummary(cacheLimit, cacheBudgetBytes) +
                            "\n超出上限自动清理最久未读的书，不影响你的原文件"
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { showCacheLimitSheet = true },
            )

            SectionLabel("漫画库")
            ListItem(
                headlineContent = { Text("漫画库目录") },
                supportingContent = { Text("管理扫描漫画的文件夹") },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable(onClick = onOpenFolders),
            )
        }
    }

    if (showCacheLimitSheet) {
        val cacheLimitSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCacheLimitSheet = false },
            sheetState = cacheLimitSheetState,
        ) {
            CacheLimitSheetContent(
                selected = cacheLimit,
                autoBudgetBytes = autoCacheBudgetBytes,
                onSelect = { limit ->
                    viewModel.setCacheLimit(limit)
                    showCacheLimitSheet = false
                },
            )
        }
    }
}

@Composable
private fun CacheLimitSheetContent(
    selected: CacheLimit,
    autoBudgetBytes: Long,
    onSelect: (CacheLimit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = "漫画缓存上限",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        CacheLimit.entries.forEach { limit ->
            ListItem(
                headlineContent = { Text(limit.label) },
                supportingContent = {
                    Text(cacheLimitDescription(limit, autoBudgetBytes))
                },
                leadingContent = {
                    RadioButton(
                        selected = selected == limit,
                        onClick = null,
                    )
                },
                modifier = Modifier.selectable(
                    selected = selected == limit,
                    onClick = { onSelect(limit) },
                    role = Role.RadioButton,
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** 整 GB 不补 .0；非整 GB 保留一位小数，以下取整 MB */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> {
        val gb = 1L shl 30
        if (bytes % gb == 0L) {
            "${bytes / gb} GB"
        } else {
            "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
        }
    }
    else -> "${bytes shr 20} MB"
}

private fun cacheLimitSummary(limit: CacheLimit, budgetBytes: Long): String =
    if (limit == CacheLimit.AUTO) {
        "${limit.label}（约 ${formatBytes(budgetBytes)}）"
    } else {
        "上限 ${limit.label}"
    }

private fun cacheLimitDescription(limit: CacheLimit, autoBudgetBytes: Long): String =
    if (limit == CacheLimit.AUTO) {
        "${limit.description}，当前约 ${formatBytes(autoBudgetBytes)}"
    } else {
        limit.description
    }

/** 圆形种子色卡：选中态 = 主题色描边 + 白色对勾（所有内置种子都偏深，白勾可读） */
@Composable
private fun SeedSwatch(
    seed: ThemeSeed,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(seed.argb))
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = Color.White,
                )
            }
        }
        Text(
            text = seed.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
