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
import com.exio.inkleaf.data.CacheLimit
import com.exio.inkleaf.data.ThemeSettings

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
    val cacheUsage by viewModel.cacheUsageBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cacheBudgetBytes = remember(cacheLimit, context) { cacheLimit.bytes(context) }
    val autoCacheBudgetBytes = remember(context) { CacheLimit.AUTO.bytes(context) }
    var showCacheLimitSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showLicensesSheet by remember { mutableStateOf(false) }
    var showFoldersSheet by rememberSaveable { mutableStateOf(false) }
    val lastPickedFolder by foldersViewModel.lastPickedFolder.collectAsStateWithLifecycle()

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
                        cacheLimitSummary(cacheLimit, cacheBudgetBytes),
                onClick = { showCacheLimitSheet = true },
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
            ThirdPartyLicensesSheetContent()
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
            supporting = "OCR 模型、运行时与图像库许可",
            onClick = onOpenLicenses,
            trailingContent = { ForwardIcon() },
        )
    }
}

@Composable
private fun ThirdPartyLicensesSheetContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SheetColumn(modifier = modifier, scrollable = true) {
        StandardSheetTitle("开源许可")
        Text(
            text = "OCR 模型与相关库版权归各自上游项目。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        THIRD_PARTY_NOTICES.forEach { notice ->
            InkleafActionListItem(
                headline = notice.name,
                supporting = "${notice.summary}\n${notice.license}",
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, notice.url.toUri()))
                },
                trailingContent = { ForwardIcon() },
            )
        }
    }
}

private data class ThirdPartyNotice(
    val name: String,
    val summary: String,
    val license: String,
    val url: String,
)

private val THIRD_PARTY_NOTICES = listOf(
    ThirdPartyNotice(
        name = "PP-OCRv6 / PaddleOCR",
        summary = "轻量文字检测与识别引擎",
        license = "Apache-2.0 · PaddlePaddle Authors",
        url = "https://github.com/PaddlePaddle/PaddleOCR",
    ),
    ThirdPartyNotice(
        name = "ONNX Runtime",
        summary = "跨平台机器学习推理运行时",
        license = "MIT · Microsoft Corporation",
        url = "https://github.com/microsoft/onnxruntime",
    ),
    ThirdPartyNotice(
        name = "OpenCV Android",
        summary = "计算机视觉基础库",
        license = "Apache-2.0 · OpenCV team",
        url = "https://github.com/opencv/opencv",
    ),
)

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
