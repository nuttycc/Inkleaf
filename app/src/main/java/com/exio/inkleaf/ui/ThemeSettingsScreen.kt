package com.exio.inkleaf.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.isSystemCurrentlyDark
import com.exio.inkleaf.data.CustomStyle
import com.exio.inkleaf.data.DarkMode
import com.exio.inkleaf.data.ThemeColorSpec
import com.exio.inkleaf.data.ThemeContrast
import com.exio.inkleaf.data.ThemeSeed
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.resolveDark
import com.exio.inkleaf.ui.theme.rememberInkleafColorScheme
import com.materialkolor.hct.Hct

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    appliedSettings: ThemeSettings,
    onBack: () -> Unit,
    onApplyTheme: (applied: ThemeSettings, committed: ThemeSettings) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeSettingsViewModel = viewModel(),
) {
    var baseline by rememberSaveable(stateSaver = ThemeSettingsSaver) {
        mutableStateOf(appliedSettings)
    }
    var draft by rememberSaveable(stateSaver = ThemeSettingsSaver) {
        mutableStateOf(appliedSettings)
    }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomColorSheet by rememberSaveable { mutableStateOf(false) }
    var showAdvancedColorSheet by rememberSaveable { mutableStateOf(false) }
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val pendingApplication by viewModel.pendingApplication.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val isDirty = draft != baseline

    // A committed draft survives Activity recreation. Uncommitted edits survive ordinary
    // configuration changes because both values are saveable and the applied settings stay equal.
    LaunchedEffect(appliedSettings) {
        if (baseline != appliedSettings) {
            if (draft == baseline || draft == appliedSettings) {
                draft = appliedSettings
            }
            baseline = appliedSettings
        }
    }
    LaunchedEffect(saveError) {
        val message = saveError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSaveError()
    }
    LaunchedEffect(pendingApplication) {
        val committed = pendingApplication ?: return@LaunchedEffect
        // Consume before recreation so a retained ViewModel cannot apply the same transaction twice.
        viewModel.consumePendingApplication()
        baseline = committed
        if (committed != appliedSettings) {
            onApplyTheme(appliedSettings, committed)
        }
    }

    fun requestBack() {
        when {
            isSaving -> Unit
            isDirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    BackHandler(onBack = { requestBack() })

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("外观与主题") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                modifier = Modifier.navigationBarsPadding(),
                tonalElevation = 3.dp,
            ) {
                Button(
                    onClick = {
                        viewModel.applyTheme(draft)
                    },
                    enabled = isDirty && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(if (isSaving) "正在应用主题…" else "应用主题")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            ThemeSpecimen(settings = draft)

            ThemeSectionLabel("颜色来源")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeSeed.entries.forEach { seed ->
                    SeedSwatch(
                        seed = seed,
                        selected = !draft.useWallpaper && !draft.useCustom && draft.seed == seed,
                        onClick = {
                            draft = draft.copy(
                                seed = seed,
                                useWallpaper = false,
                                useCustom = false,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                CustomSwatch(
                    customArgb = draft.customArgb,
                    selected = !draft.useWallpaper && draft.useCustom,
                    onClick = { showCustomColorSheet = true },
                    modifier = Modifier.weight(1f),
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    checked = draft.useWallpaper,
                    onCheckedChange = { draft = draft.copy(useWallpaper = it) },
                    supportingContent = { Text("使用 Android 壁纸生成配色") },
                    trailingContent = {
                        Switch(checked = draft.useWallpaper, onCheckedChange = null)
                    },
                ) { Text("壁纸取色") }
            }

            ThemeSectionLabel("明暗模式")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                DarkMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = draft.darkMode == mode,
                        onClick = { draft = draft.copy(darkMode = mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                    ) {
                        Text(mode.label)
                    }
                }
            }

            ThemeSectionLabel("配色规格")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                ThemeColorSpec.entries.forEachIndexed { index, spec ->
                    SegmentedButton(
                        selected = draft.colorSpec == spec,
                        onClick = { draft = draft.copy(colorSpec = spec) },
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
                checked = draft.useAmoled,
                onCheckedChange = { draft = draft.copy(useAmoled = it) },
                supportingContent = { Text("深色模式使用纯黑表面；浅色模式下保留此选择") },
                trailingContent = { Switch(checked = draft.useAmoled, onCheckedChange = null) },
            ) { Text("AMOLED 黑色") }

            ThemeSectionLabel("高级")
            InkleafActionListItem(
                headline = "高级配色",
                supporting = "${draft.customStyle.label} · ${draft.contrast.label}",
                onClick = { showAdvancedColorSheet = true },
            )
        }
    }

    if (showCustomColorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCustomColorSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            CustomColorSheetContent(
                customArgb = draft.customArgb,
                onPickColor = { argb ->
                    draft = draft.copy(
                        customArgb = argb,
                        useCustom = true,
                        useWallpaper = false,
                    )
                },
            )
        }
    }

    if (showAdvancedColorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAdvancedColorSheet = false },
            sheetState = rememberExpandOnlySheetState(),
        ) {
            AdvancedColorSheetContent(
                settings = draft,
                onPickStyle = { draft = draft.copy(customStyle = it) },
                onPickContrast = { draft = draft.copy(contrast = it) },
                onReset = {
                    draft = draft.copy(
                        customStyle = CustomStyle.STANDARD,
                        contrast = ThemeContrast.DEFAULT,
                    )
                },
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃主题修改？") },
            text = { Text("尚未应用的主题选择会丢失。") },
            confirmButton = {
                TextButton(onClick = onBack) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun ThemeSpecimen(settings: ThemeSettings) {
    val systemIsDark = isSystemCurrentlyDark()
    val previewIsDark = settings.darkMode.resolveDark(systemIsDark)
    val typography = MaterialTheme.typography
    val colorScheme = rememberInkleafColorScheme(settings, previewIsDark)

    key(settings, previewIsDark) {
        MaterialExpressiveTheme(colorScheme = colorScheme, typography = typography) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("主题样张", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = previewDescription(settings, previewIsDark),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) {
                            Text("主要操作")
                        }
                        Switch(checked = true, onCheckedChange = null)
                    }
                    ListItem(
                        selected = true,
                        onClick = {},
                        leadingContent = {
                            RadioButton(selected = true, onClick = null)
                        },
                        supportingContent = { Text("正文、选中态与表面层级") },
                    ) {
                        Text("阅读界面设置")
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = "错误与警示颜色",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun previewDescription(settings: ThemeSettings, isDark: Boolean): String {
    val source = when {
        settings.useWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "壁纸取色"
        settings.useCustom -> "自定义色"
        else -> settings.seed.label
    }
    val appearance = if (isDark) "深色" else "浅色"
    return "$source · $appearance · ${settings.customStyle.label} · ${settings.contrast.label}"
}

@Composable
private fun AdvancedColorSheetContent(
    settings: ThemeSettings,
    onPickStyle: (CustomStyle) -> Unit,
    onPickContrast: (ThemeContrast) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(modifier = modifier, scrollable = true, selectable = true) {
        StandardSheetTitle("高级配色")
        if (settings.useWallpaper) {
            InkleafInfoListItem(
                headline = "壁纸取色正在生效",
                supporting = "高级参数会保留，并在切回种子色时生效",
            )
        }
        ThemeSectionLabel("调色风格")
        CustomStyle.entries.forEach { style ->
            InkleafChoiceListItem(
                headline = style.label,
                selected = settings.customStyle == style,
                onClick = { onPickStyle(style) },
                supportingContent = { Text(paletteStyleDescription(style)) },
            )
        }
        ThemeSectionLabel("对比度")
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

@Composable
private fun ThemeSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Modifier.selectionRing(selected: Boolean): Modifier =
    if (selected) border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else this

@Composable
private fun SeedSwatch(
    seed: ThemeSeed,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 72.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
        Text(seed.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CustomSwatch(
    customArgb: Long?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 72.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
            when {
                selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                customArgb == null -> Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.primary,
                )
            }
        }
        Text("自定义", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CustomColorSheetContent(
    customArgb: Long?,
    onPickColor: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedHue = remember(customArgb) {
        customArgb
            ?.let { Hct.fromInt(it.toInt()) }
            ?.takeIf { it.chroma > 10.0 }
            ?.hue
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
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
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rowHues.forEach { hue ->
                    HueCell(
                        color = Color(seedArgbOf(hue)),
                        selected = selectedHue != null &&
                                hueDistance(selectedHue, hue) < HUE_MATCH_TOLERANCE,
                        onClick = { onPickColor(seedArgbOf(hue)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        var hexText by rememberSaveable(customArgb) {
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
    }
}

@Composable
private fun HueCell(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .selectionRing(selected),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                )
            }
        }
    }
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

private val ThemeSettingsSaver = listSaver<ThemeSettings, Any>(
    save = {
        listOf(
            it.seed.name,
            it.darkMode.name,
            it.useWallpaper,
            it.customArgb ?: NO_CUSTOM_COLOR,
            it.useCustom,
            it.customStyle.name,
            it.colorSpec.name,
            it.contrast.name,
            it.useAmoled,
        )
    },
    restore = {
        ThemeSettings(
            seed = enumValueOf(it[0] as String),
            darkMode = enumValueOf(it[1] as String),
            useWallpaper = it[2] as Boolean,
            customArgb = (it[3] as Long).takeUnless { value -> value == NO_CUSTOM_COLOR },
            useCustom = it[4] as Boolean,
            customStyle = enumValueOf(it[5] as String),
            colorSpec = enumValueOf(it[6] as String),
            contrast = enumValueOf(it[7] as String),
            useAmoled = it[8] as Boolean,
        )
    },
)

private val CUSTOM_HUES = List(12) { it * 30.0 }
private const val HUE_MATCH_TOLERANCE = 15.0
private const val NO_CUSTOM_COLOR = Long.MIN_VALUE

private fun seedArgbOf(hue: Double): Long =
    Hct.from(hue, 48.0, 50.0).toInt().toLong() and 0xFFFFFFFFL

private fun hueDistance(a: Double, b: Double): Double {
    val distance = kotlin.math.abs(a - b)
    return kotlin.math.min(distance, 360.0 - distance)
}

private fun hexOf(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

private fun parseHexColor(text: String): Long? {
    val body = text.removePrefix("#")
    if (body.length != 6) return null
    return body.toLongOrNull(16)?.let { 0xFF000000L or it }
}
