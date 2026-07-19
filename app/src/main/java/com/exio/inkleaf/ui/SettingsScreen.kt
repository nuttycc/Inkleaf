package com.exio.inkleaf.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.data.CacheLimit
import com.exio.inkleaf.data.ThemeSettings

/** General settings. Theme editing lives on its own route with an explicit apply boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeSettings: ThemeSettings,
    onBack: () -> Unit,
    onOpenThemeSettings: () -> Unit,
    onOpenModelManager: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    foldersViewModel: FoldersViewModel = viewModel(),
    enhancementModelsViewModel: EnhancementModelsViewModel = viewModel(),
) {
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsageBytes.collectAsStateWithLifecycle()
    val installedModelCount by enhancementModelsViewModel.installedCount.collectAsStateWithLifecycle()
    val installedModelBytes by enhancementModelsViewModel.installedBytes.collectAsStateWithLifecycle()
    val bundledModelCount = enhancementModelsViewModel.bundledCount
    val modelsChecking by enhancementModelsViewModel.isChecking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cacheBudgetBytes = remember(cacheLimit, context) { cacheLimit.bytes(context) }
    val autoCacheBudgetBytes = remember(context) { CacheLimit.AUTO.bytes(context) }
    var showCacheLimitSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showLicensesSheet by remember { mutableStateOf(false) }
    var showFoldersSheet by rememberSaveable { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
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
                headline = "漫画缓存上限",
                supporting = "当前占用 ${formatBytes(cacheUsage)} · " +
                        cacheLimitSummary(cacheLimit, cacheBudgetBytes) +
                        "\n超出上限自动清理最久未读的书，不影响你的原文件",
                onClick = { showCacheLimitSheet = true },
                trailingContent = { ForwardIcon() },
            )
            InkleafActionListItem(
                headline = "图像增强模型",
                supporting = when {
                    modelsChecking -> "正在检查模型文件…"
                    installedModelCount == 0 -> "已内置 $bundledModelCount 个模型包 · 可继续下载更多模型"
                    else -> "已内置 $bundledModelCount 个 · 已下载 $installedModelCount 个 · 占用 " +
                            formatFileSize(installedModelBytes)
                },
                onClick = onOpenModelManager,
                trailingContent = { ForwardIcon() },
            )

            SectionLabel("漫画库")
            InkleafActionListItem(
                headline = "漫画库目录",
                supporting = "管理扫描漫画的文件夹",
                onClick = { showFoldersSheet = true },
                trailingContent = { ForwardIcon() },
            )

            SectionLabel("关于")
            InkleafActionListItem(
                headline = "关于 Inkleaf",
                supporting = "版本、GitHub 与项目信息",
                onClick = { showAboutSheet = true },
                trailingContent = { ForwardIcon() },
            )
        }
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
                onAddFolder = { treePicker.launch(null) },
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
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                },
                onOpenLicenses = {
                    showAboutSheet = false
                    showLicensesSheet = true
                },
            )
        }
    }

    if (showLicensesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLicensesSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            ThirdPartyLicensesSheetContent(
                text = remember(context) {
                    context.assets.open(MODEL_LICENSE_NOTICE_ASSET).bufferedReader()
                        .use { it.readText() }
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
    val source = when {
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
    onOpenLicenses: () -> Unit,
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
        InkleafActionListItem(
            headline = "开源许可",
            supporting = "模型版权、许可证与来源",
            onClick = onOpenLicenses,
            trailingContent = { ForwardIcon() },
        )
    }
}

@Composable
private fun ThirdPartyLicensesSheetContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, scrollable = true) {
        StandardSheetTitle("开源许可")
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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

private const val GITHUB_URL = "https://github.com/nuttycc/inkleaf"
private const val MODEL_LICENSE_NOTICE_ASSET = "THIRD_PARTY_MODEL_LICENSES.txt"

private fun appVersionName(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return packageInfo.versionName ?: "未知"
}
