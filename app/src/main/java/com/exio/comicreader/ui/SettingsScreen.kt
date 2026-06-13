package com.exio.comicreader.ui

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.comicreader.data.CacheLimit
import com.exio.comicreader.data.CustomStyle
import com.exio.comicreader.data.DarkMode
import com.exio.comicreader.data.ThemeSeed
import com.exio.comicreader.data.ThemeSettings
import com.exio.comicreader.ui.theme.isDarkTheme
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
    var showCustomColorSheet by remember { mutableStateOf(false) }
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
                // 透明底色：顶栏不保有独立主题色，换肤瞬切才不会被 M3 内部
                // 弹簧拖慢（策略与前提见 Theme.kt 的换肤注释）。本屏内容不会
                // 滚到顶栏底下；若将来加 scrollBehavior 需重新评估
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                    headlineContent = { Text("壁纸取色") },
                    supportingContent = { Text("跟随系统壁纸生成配色（Material You）") },
                    trailingContent = {
                        Switch(
                            checked = themeSettings.useWallpaper,
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
                        selected = themeSettings.darkMode == mode,
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
                modifier = Modifier.clickable { showFoldersSheet = true },
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
                customStyle = themeSettings.customStyle,
                // 预览要和全 App 当前的深浅状态一致，复用 Theme.kt 的判定
                isDark = themeSettings.darkMode.isDarkTheme(),
                onPickColor = viewModel::setCustomColor,
                onPickStyle = viewModel::setCustomStyle,
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
        StandardSheetTitle("漫画缓存上限")

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

/** 取色网格的色相档位：每 30° 一格，12 格覆盖整个色相环（HCT 色相空间） */
private val CUSTOM_HUES = List(12) { it * 30.0 }

/** 选中态的色相匹配容差：相邻格间隔 30°，取半格 */
private const val HUE_MATCH_TOLERANCE = 15.0

/**
 * 自定义取色面板：离散色相网格 + 浓淡三档 + hex 兜底。
 *
 * 为什么不是连续滑条/HSV 取色器：M3 调色只保留种子的色相，输出里
 * 可分辨的主题只有几十种——连续输入是假精度（大段拖动毫无变化）。
 * 网格每格直接显示"调色后的实际主色"，所选即所得。
 *
 * 为什么没有"打开即应用"、没有本地预览状态：点格子才写入，DataStore
 * 链路让全 App（含本面板）即时换色，面板本身就是预览。打开 sheet
 * 不触发任何全局写入——之前的实现在进场动画期间 debounce 落地一次
 * 全 App 重新调色，重组正砸在动画中间，这就是进场卡顿的根因。
 */
@Composable
private fun CustomColorSheetContent(
    customArgb: Long?,
    customStyle: CustomStyle,
    isDark: Boolean,
    onPickColor: (Long) -> Unit,
    onPickStyle: (CustomStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 选中格 = 已存颜色 HCT 色相落入容差内的格。用容差而非相等判定，
    // hex 输入的任意颜色也能在网格上亮起"它所属的色相家族"
    val selectedHue = remember(customArgb) {
        customArgb
            ?.let { Hct.fromInt(it.toInt()) }
            ?.takeIf { it.chroma > 10.0 }  // 灰色不属于任何色相格
            ?.hue
    }

    // 每格显示"调色后的实际 primary"：单点 HCT 求解，不生成整套
    // scheme（12 套 scheme 会在进场首帧造成可感知的组合耗时）。
    // tone 是 M3 primary 的明暗档位；chroma 跟浓淡档对齐
    val cellTone = if (isDark) 80.0 else 40.0
    val cellChroma = when (customStyle) {
        CustomStyle.MUTED -> 12.0
        CustomStyle.STANDARD -> 36.0
        // 超出色域的部分由 HCT 求解器钳到该色相最大可显示彩度
        CustomStyle.VIVID -> 100.0
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
                        color = Color(Hct.from(hue, cellChroma, cellTone).toInt()),
                        selected = selectedHue != null &&
                                hueDistance(selectedHue, hue) < HUE_MATCH_TOLERANCE,
                        onClick = { onPickColor(seedArgbOf(hue)) },
                    )
                }
            }
        }

        Text(
            text = "浓淡",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CustomStyle.entries.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = customStyle == style,
                    onClick = { onPickStyle(style) },
                    shape = SegmentedButtonDefaults.itemShape(index, CustomStyle.entries.size),
                ) {
                    Text(style.label)
                }
            }
        }

        // hex 兜底：精确指定品牌色的出口。键入满 6 位合法字符即应用，
        // 应用后 remember(customArgb) 会用规范格式（#RRGGBB）重置文本
        var hexText by remember(customArgb) {
            mutableStateOf(customArgb?.let(::hexOf) ?: "")
        }
        OutlinedTextField(
            value = hexText,
            onValueChange = { input ->
                val cleaned = input.trim().uppercase()
                val body = cleaned.removePrefix("#")
                if (body.length <= 6 && body.all { it in '0'..'9' || it in 'A'..'F' }) {
                    hexText = cleaned
                    parseHexColor(cleaned)?.let(onPickColor)
                }
            },
            label = { Text("Hex") },
            placeholder = { Text("#RRGGBB") },
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
                // 格子明度随深浅模式取 tone 40/80 两个档位，勾的颜色
                // 按格子实际亮度反向，深浅模式下都可读
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}

/**
 * 点选色相格时实际存储的种子：固定 chroma/tone 的规范色。三种浓淡档
 * 的调色都只取种子的色相，chroma 取 48 仅为了让色卡入口显示得鲜明、
 * 且稳过 customSeedStyle 的灰色判定
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
