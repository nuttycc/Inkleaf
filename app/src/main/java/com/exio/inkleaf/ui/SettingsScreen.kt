package com.exio.inkleaf.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.data.CacheLimit
import com.exio.inkleaf.data.CustomStyle
import com.exio.inkleaf.data.DarkMode
import com.exio.inkleaf.data.ThemeColorSpec
import com.exio.inkleaf.data.ThemeContrast
import com.exio.inkleaf.data.ThemeSeed
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.ui.theme.isDarkTheme
import com.exio.inkleaf.ui.theme.resolvedPaletteStyle
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.hct.Hct

/** 设置页：主题（种子色卡 / 深浅模式 / 壁纸取色）+ 漫画库目录管理 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    // 主题状态由顶层（MainActivity）下传：那里的收集在 splash 期间就已
    // 完成，首帧即真实值。若在这里自建 stateIn 订阅，初值到真实值的
    // 修正会被 Switch 的滑动动画"演"出来（进页时开关突然滑动）
    themeSettings: ThemeSettings,
    onBack: () -> Unit,
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
    var showCustomColorSheet by remember { mutableStateOf(false) }
    var showAdvancedColorSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showLicensesSheet by remember { mutableStateOf(false) }
    // rememberSaveable：目录选择器是外部全屏 Activity，期间进程可能被杀；
    // 重建后 sheet 必须恢复打开，选择结果才有处展示
    var showFoldersSheet by rememberSaveable { mutableStateOf(false) }

    // OpenDocumentTree：系统目录选择器，授权的是整棵子树。launcher 放在
    // 屏幕层级（而非 sheet 内容里）——进程被杀重建后它随设置页立即重新
    // 注册，暂存在 ActivityResultRegistry 里的结果才投递得出去
    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
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
                // Keep both flexible app-bar states transparent. The page background remains the
                // single animated surface during collapse and the single instant surface on a
                // whole-app theme change; see Theme.kt for the synchronization constraint.
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
                        selected = !themeSettings.useWallpaper &&
                                !themeSettings.useCustom &&
                                themeSettings.seed == seed,
                        onClick = { viewModel.setSeed(seed) },
                    )
                }
                // 自定义取色：未设置过时显示色相环渐变，设置过后显示该颜色
                CustomSwatch(
                    customArgb = themeSettings.customArgb,
                    selected = !themeSettings.useWallpaper && themeSettings.useCustom,
                    onClick = { showCustomColorSheet = true },
                )
            }

            // 壁纸取色：Material You 动态色仅 Android 12+ 提供
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    checked = themeSettings.useWallpaper,
                    onCheckedChange = viewModel::setUseWallpaper,
                    supportingContent = { Text("跟随系统壁纸生成配色（Material You）") },
                    trailingContent = {
                        Switch(
                            checked = themeSettings.useWallpaper,
                            onCheckedChange = null,
                        )
                    },
                ) { Text("壁纸取色") }
            }

            SectionLabel("深浅模式")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                DarkMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeSettings.darkMode == mode,
                        onClick = { viewModel.setDarkMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                    ) {
                        Text(mode.label)
                    }
                }
            }

            SectionLabel("配色规格")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                ThemeColorSpec.entries.forEachIndexed { index, spec ->
                    SegmentedButton(
                        selected = themeSettings.colorSpec == spec,
                        onClick = { viewModel.setColorSpec(spec) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index,
                            ThemeColorSpec.entries.size,
                        ),
                    ) {
                        Text(spec.label)
                    }
                }
            }
            ListItem(
                checked = themeSettings.useAmoled,
                onCheckedChange = viewModel::setUseAmoled,
                supportingContent = { Text("深色模式使用纯黑表面") },
                trailingContent = {
                    Switch(
                        checked = themeSettings.useAmoled,
                        onCheckedChange = null,
                    )
                },
            ) { Text("AMOLED 黑色") }
            InkleafActionListItem(
                headline = "高级配色",
                supporting = "${themeSettings.customStyle.label} · ${themeSettings.contrast.label}",
                onClick = { showAdvancedColorSheet = true },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
            )

            SectionLabel("存储")
            InkleafActionListItem(
                headline = "漫画缓存上限",
                supporting = "当前占用 ${formatBytes(cacheUsage)} · " +
                        cacheLimitSummary(cacheLimit, cacheBudgetBytes) +
                        "\n超出上限自动清理最久未读的书，不影响你的原文件",
                onClick = { showCacheLimitSheet = true },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
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
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
            )

            SectionLabel("漫画库")
            InkleafActionListItem(
                headline = "漫画库目录",
                supporting = "管理扫描漫画的文件夹",
                onClick = { showFoldersSheet = true },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
            )

            SectionLabel("关于")
            InkleafActionListItem(
                headline = "关于 Inkleaf",
                supporting = "版本、GitHub 与项目信息",
                onClick = { showAboutSheet = true },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
            )
        }
    }

    if (showCustomColorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCustomColorSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            CustomColorSheetContent(
                customArgb = themeSettings.customArgb,
                onPickColor = viewModel::setCustomColor,
            )
        }
    }

    if (showAdvancedColorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAdvancedColorSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            AdvancedColorSheetContent(
                settings = themeSettings,
                onPickStyle = viewModel::setCustomStyle,
                onPickContrast = viewModel::setContrast,
                onReset = viewModel::resetAdvancedColorSettings,
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

/**
 * Hosts options that are useful for deliberate theme tuning but too dense for the main settings
 * page. Changes still apply immediately; the nested preview is the only place that interpolates
 * between schemes so whole-app theme changes remain deterministic.
 */
@Composable
private fun AdvancedColorSheetContent(
    settings: ThemeSettings,
    onPickStyle: (CustomStyle) -> Unit,
    onPickContrast: (ThemeContrast) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(
        modifier = modifier,
        scrollable = true,
        selectable = true,
    ) {
        StandardSheetTitle("高级配色")
        if (settings.useWallpaper) {
            InkleafInfoListItem(
                headline = "壁纸取色正在生效",
                supporting = "高级参数会保留，并在切回预设或自定义种子色时生效",
            )
        }
        AnimatedThemePreview(
            settings = settings,
            wallpaperActive = settings.useWallpaper,
        )

        SectionLabel("调色风格")
        CustomStyle.entries.forEach { style ->
            InkleafChoiceListItem(
                headline = style.label,
                selected = settings.customStyle == style,
                onClick = { onPickStyle(style) },
                supportingContent = { Text(paletteStyleDescription(style)) },
            )
        }

        SectionLabel("对比度")
        ThemeContrast.entries.forEach { contrast ->
            InkleafChoiceListItem(
                headline = contrast.label,
                selected = settings.contrast == contrast,
                onClick = { onPickContrast(contrast) },
                supportingContent = { Text(contrast.description) },
            )
        }

        TextButton(
            onClick = onReset,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("恢复推荐值")
        }
    }
}

/** Uses Material Kolor's animated wrapper only inside the preview, never around the app root. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedThemePreview(
    settings: ThemeSettings,
    wallpaperActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDark = settings.darkMode.isDarkTheme()
    val seedArgb = if (settings.useCustom) {
        settings.customArgb ?: settings.seed.argb
    } else {
        settings.seed.argb
    }

    DynamicMaterialExpressiveTheme(
        seedColor = Color(seedArgb),
        isDark = isDark,
        isAmoled = isDark && settings.useAmoled,
        style = resolvedPaletteStyle(seedArgb, settings.customStyle.style),
        contrastLevel = settings.contrast.contrast.value,
        specVersion = settings.colorSpec.specVersion,
        animate = true,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Ink in Motion", style = MaterialTheme.typography.titleMedium)
            Text(
                if (wallpaperActive) {
                    "切回种子色后使用的局部动画预览"
                } else {
                    "当前配色的局部动画预览"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PreviewColor(MaterialTheme.colorScheme.primary)
                PreviewColor(MaterialTheme.colorScheme.secondary)
                PreviewColor(MaterialTheme.colorScheme.tertiary)
                PreviewColor(MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

@Composable
private fun PreviewColor(color: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun paletteStyleDescription(style: CustomStyle): String = when (style) {
    CustomStyle.MUTED -> "低彩度、安静的中性色调"
    CustomStyle.STANDARD -> "平衡、熟悉的 Material 配色"
    CustomStyle.VIVID -> "提高主色与辅助色的鲜明度"
    CustomStyle.EXPRESSIVE -> "偏移种子色色相，获得更活跃的组合"
    CustomStyle.FIDELITY -> "尽量保留种子色在主要容器中的外观"
    CustomStyle.CONTENT -> "围绕内容色生成相邻与互补颜色"
    CustomStyle.MONOCHROME -> "只使用黑、白与灰阶角色"
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
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
        )
        InkleafActionListItem(
            headline = "开源许可",
            supporting = "模型版权、许可证与来源",
            onClick = onOpenLicenses,
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
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
                supportingContent = {
                    Text(cacheLimitDescription(limit, autoBudgetBytes))
                },
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

/** 色卡选中态的统一视觉：主题色描边圆环（预设/自定义/取色网格三处共用） */
@Composable
private fun Modifier.selectionRing(selected: Boolean): Modifier =
    if (selected) {
        border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        this
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
                .selectionRing(selected),
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

/** 自定义色卡：未设置时显示为中性的"添加颜色"入口，避免默认态抢主题色焦点 */
@Composable
private fun CustomSwatch(
    customArgb: Long?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (customArgb != null) {
                        Modifier.background(Color(customArgb))
                    } else {
                        Modifier
                            .background(colors.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = colors.outline.copy(alpha = 0.45f),
                                shape = CircleShape,
                            )
                    }
                )
                .selectionRing(selected),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = Color.White,
                )
            } else if (customArgb == null) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.primary,
                )
            }
        }
        Text(
            text = "自定义",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Twelve canonical HCT hues cover the full color wheel in 30-degree steps. */
private val CUSTOM_HUES = List(12) { it * 30.0 }

/** Half a grid step lets arbitrary hex colors select their nearest hue family. */
private const val HUE_MATCH_TOLERANCE = 15.0

/**
 * Selects a custom seed through a discrete hue grid with hexadecimal input as an exact fallback.
 *
 * A continuous HSV picker suggests precision that dynamic color does not preserve. The grid owns
 * only seed selection; spec, palette strategy, and contrast determine the final scheme, which is
 * shown by the animated preview in advanced settings.
 *
 * Opening the sheet never writes to DataStore. Only an explicit pick applies a new seed, avoiding
 * a whole-app recomposition during the sheet entrance animation.
 */
@Composable
private fun CustomColorSheetContent(
    customArgb: Long?,
    onPickColor: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Match by hue family so arbitrary hex seeds still select the nearest canonical swatch.
    val selectedHue = remember(customArgb) {
        customArgb
            ?.let { Hct.fromInt(it.toInt()) }
            ?.takeIf { it.chroma > 10.0 } // Gray does not belong to a hue family.
            ?.hue
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = "自定义主题色",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        CUSTOM_HUES.chunked(6).forEach { rowHues ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowHues.forEach { hue ->
                    HueCell(
                        color = Color(seedArgbOf(hue)),
                        selected = selectedHue != null &&
                                hueDistance(selectedHue, hue) < HUE_MATCH_TOLERANCE,
                        onClick = { onPickColor(seedArgbOf(hue)) },
                    )
                }
            }
        }

        // hex 兜底：精确指定品牌色的出口。键入满 6 位合法字符即应用，
        // 应用后 remember(customArgb) 会用规范格式（#RRGGBB）重置文本
        var hexText by remember(customArgb) {
            mutableStateOf(customArgb?.let(::hexOf) ?: "")
        }
        InkleafPrecisionField(
            value = hexText,
            onValueChange = { input ->
                val cleaned = input.trim().uppercase()
                val body = cleaned.removePrefix("#")
                if (body.length <= 6 && body.all { it in '0'..'9' || it in 'A'..'F' }) {
                    hexText = cleaned
                    parseHexColor(cleaned)?.let(onPickColor)
                }
            },
            label = "Hex",
            placeholder = "#RRGGBB",
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
    }
}

/** 网格单元：圆形色块，选中态与预设色卡同款（描边 + 对勾） */
@Composable
private fun HueCell(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .selectionRing(selected),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选中",
                // 格子明度随深浅模式取 tone 40/80 两个档位，勾的颜色
                // 按格子实际亮度反向，深浅模式下都可读
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}

/**
 * Stores a canonical seed for each hue. Chroma keeps the swatch legible and safely above the
 * low-saturation Neutral threshold; the selected palette strategy generates the final scheme.
 */
private fun seedArgbOf(hue: Double): Long =
    Hct.from(hue, 48.0, 50.0).toInt().toLong() and 0xFFFFFFFFL

/** 色相环上的最短角距离（处理 350° 与 10° 实际只差 20° 的回绕） */
private fun hueDistance(a: Double, b: Double): Double {
    val d = kotlin.math.abs(a - b)
    return kotlin.math.min(d, 360.0 - d)
}

private fun hexOf(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

/** 仅接受 6 位 RGB（可带 #），alpha 固定不透明 */
private fun parseHexColor(text: String): Long? {
    val body = text.removePrefix("#")
    if (body.length != 6) return null
    return body.toLongOrNull(16)?.let { 0xFF000000L or it }
}
