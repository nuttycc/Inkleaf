package com.exio.inkleaf.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.DeveloperMode
import com.exio.inkleaf.data.CacheLimit
import com.exio.inkleaf.data.ThemeSettings
import com.ms.square.debugoverlay.DebugOverlay

/** General settings. Theme editing lives on its own route with an explicit apply boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeSettings: ThemeSettings,
    onBack: () -> Unit,
    onOpenThemeSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    foldersViewModel: FoldersViewModel = viewModel(),
) {
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsage.collectAsStateWithLifecycle()
    val cacheBudgetBytes by viewModel.cacheBudgetBytes.collectAsStateWithLifecycle()
    val autoCacheBudgetBytes by viewModel.autoCacheBudgetBytes.collectAsStateWithLifecycle()
    val isClearingOnlineCache by viewModel.isClearingOnlineCache.collectAsStateWithLifecycle()
    val cacheMessage by viewModel.cacheMessage.collectAsStateWithLifecycle()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCacheLimitSheet by remember { mutableStateOf(false) }
    var showClearOnlineCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showFoldersSheet by rememberSaveable { mutableStateOf(false) }
    val lastPickedFolder by foldersViewModel.lastPickedFolder.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SnackbarMessageEffect(
        message = cacheMessage,
        hostState = snackbarHostState,
        onConsumed = viewModel::consumeCacheMessage,
    )

    val treePicker =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) {
            uri ->
            if (uri != null) foldersViewModel.addFolder(uri)
        }
    val topAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
        ) {
            SectionLabel("个性化")
            InkleafActionListItem(
                headline = "主题",
                supporting = themeSummary(themeSettings),
                onClick = onOpenThemeSettings,
                trailingContent = { ForwardIcon() },
            )

            SectionLabel("存储")
            InkleafActionListItem(
                headline = "阅读缓存上限",
                supporting =
                    "已用 ${formatBytes(cacheUsage.totalBytes)} / " +
                        "上限 ${formatBytes(cacheBudgetBytes)}\n" +
                        "本地副本 ${formatBytes(cacheUsage.localCopiesBytes)} · " +
                        "在线正文 ${formatBytes(cacheUsage.onlineBodyBytes)} · " +
                        "缩略图 ${formatBytes(cacheUsage.thumbnailsBytes)}",
                onClick = { showCacheLimitSheet = true },
                trailingContent = { ForwardIcon() },
            )
            InkleafActionListItem(
                headline = "清除在线漫画缓存",
                supporting =
                    if (isClearingOnlineCache) {
                        "正在清除在线正文和在线阅读缩略图"
                    } else {
                        "预计可释放 ${formatBytes(cacheUsage.reclaimableOnlineBytes)}"
                    },
                onClick = {
                    if (!isClearingOnlineCache) showClearOnlineCacheDialog = true
                },
            )
            SectionLabel("漫画库")
            InkleafActionListItem(
                headline = "漫画库目录",
                supporting = "管理扫描漫画的文件夹",
                onClick = { showFoldersSheet = true },
                trailingContent = { ForwardIcon() },
            )

            SectionLabel("开发者")
            InkleafActionListItem(
                headline = "开发者模式",
                supporting = "显示运行时指标并启用调试控制台",
                onClick = { viewModel.setDeveloperModeEnabled(!developerModeEnabled) },
                trailingContent = {
                    Switch(checked = developerModeEnabled, onCheckedChange = null)
                },
            )
            if (developerModeEnabled) {
                InkleafActionListItem(
                    headline = "调试控制台",
                    supporting = "查看日志、网络请求、性能与退出记录",
                    onClick = { DebugOverlay.openPanel(context) },
                    trailingContent = { ForwardIcon() },
                )
            }

            SectionLabel("关于")
            InkleafActionListItem(
                headline = "关于 Inkleaf",
                supporting = "版本、GitHub 与项目信息",
                onClick = { showAboutSheet = true },
                trailingContent = { ForwardIcon() },
            )
        }
    }

    if (showClearOnlineCacheDialog) {
        ConfirmDialog(
            title = "清除在线漫画缓存？",
            text =
                "这会删除在线正文页和在线阅读缩略图。不会影响本地漫画、收藏、书签、" +
                    "历史、阅读进度或插件登录状态。",
            confirmLabel = "清除",
            onConfirm = {
                showClearOnlineCacheDialog = false
                viewModel.clearOnlineCache()
            },
            onDismiss = { showClearOnlineCacheDialog = false },
        )
    }

    if (showCacheLimitSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCacheLimitSheet = false },
            sheetState = rememberExpandOnlySheetState(),
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

    if (showFoldersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFoldersSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            FoldersSheetContent(
                onAddFolder = { treePicker.launch(buildFolderPickerInitialUri(lastPickedFolder)) },
                viewModel = foldersViewModel,
            )
        }
    }

    if (showAboutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAboutSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            AboutSheetContent(
                versionName = remember(context) { appVersionName(context) },
                onOpenGitHub = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
                },
            )
        }
    }
}

@Composable
private fun ForwardIcon() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
}

private fun themeSummary(settings: ThemeSettings): String {
    val source =
        when {
            settings.useWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "壁纸取色"
            settings.useCustom -> "自定义色"
            else -> settings.seed.label
        }
    return "$source · ${settings.darkMode.label}"
}

@Composable
private fun AboutSheetContent(
    versionName: String,
    onOpenGitHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier) {
        StandardSheetTitle("关于 Inkleaf")
        InkleafInfoListItem(headline = "Inkleaf", supporting = "本地漫画阅读器")
        InkleafInfoListItem(headline = "版本", supporting = versionName)
        InkleafActionListItem(
            headline = "GitHub",
            supporting = GITHUB_URL,
            onClick = onOpenGitHub,
            trailingContent = { ForwardIcon() },
        )
    }
}

@Composable
private fun CacheLimitSheetContent(
    selected: CacheLimit,
    autoBudgetBytes: Long,
    onSelect: (CacheLimit) -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, selectable = true) {
        StandardSheetTitle("漫画缓存上限")
        Text(
            text = "超出上限自动清理最久未读的书，不影响原文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        CacheLimit.entries.forEach { limit ->
            InkleafChoiceListItem(
                headline = limit.label,
                selected = selected == limit,
                onClick = { onSelect(limit) },
                supportingContent = { Text(cacheLimitDescription(limit, autoBudgetBytes)) },
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
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes <= 0L -> "0 B"
        bytes < 1L shl 10 -> "$bytes B"
        bytes < 1L shl 20 -> {
            val kiB = 1L shl 10
            if (bytes % kiB == 0L) {
                "${bytes / kiB} KiB"
            } else {
                "%.1f KiB".format(bytes / 1024f)
            }
        }
        bytes < 1L shl 30 -> "${bytes shr 20} MB"

        else -> {
            val gb = 1L shl 30
            if (bytes % gb == 0L) {
                "${bytes / gb} GB"
            } else {
                "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
            }
        }
    }

private fun cacheLimitDescription(limit: CacheLimit, autoBudgetBytes: Long): String =
    if (limit == CacheLimit.AUTO) {
        "${limit.description}，当前约 ${formatBytes(autoBudgetBytes)}"
    } else {
        limit.description
    }

private const val GITHUB_URL = "https://github.com/nuttycc/inkleaf"

private fun appVersionName(context: Context): String {
    val packageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }
    return packageInfo.versionName ?: "未知"
}
