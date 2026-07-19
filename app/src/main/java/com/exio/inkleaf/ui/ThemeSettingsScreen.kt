package com.exio.inkleaf.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.data.CustomStyle
import com.exio.inkleaf.data.DarkMode
import com.exio.inkleaf.data.ThemeColorSpec
import com.exio.inkleaf.data.ThemeContrast
import com.exio.inkleaf.data.ThemeSeed
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.resolveDark
import com.exio.inkleaf.isSystemCurrentlyDark
import com.exio.inkleaf.ui.theme.rememberInkleafColorScheme
import com.materialkolor.hct.Hct
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var showCustomColorSheet by rememberSaveable { mutableStateOf(false) }
    var showAdvancedColorSheet by rememberSaveable { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollState = rememberScrollState()
    val advancedSheetScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val systemIsDark = isSystemCurrentlyDark()
    val previewIsDark = draft.darkMode.resolveDark(systemIsDark)
    val appliedIsDark = isSystemInDarkTheme()
    val typography = MaterialTheme.typography
    val previewColorScheme = rememberInkleafColorScheme(draft, previewIsDark)

    viewModel.initialize(appliedSettings)
    PreviewSystemBars(isDark = previewIsDark, appliedIsDark = appliedIsDark)

    // Saveable editor state survives configuration changes. Baseline tracks the Activity snapshot
    // so a recreated Activity can adopt a newer persisted theme without overwriting active edits.
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
        // A modal sheet owns a separate Dialog window and would cover the editor Snackbar.
        showCustomColorSheet = false
        showAdvancedColorSheet = false
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSaveError()
    }
    LaunchedEffect(draft) {
        delay(THEME_AUTOSAVE_DEBOUNCE_MS)
        viewModel.persistTheme(draft)
    }

    fun requestBack() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            var latestDraft = draft
            while (true) {
                if (!viewModel.persistTheme(latestDraft)) {
                    isClosing = false
                    return@launch
                }
                val currentDraft = draft
                if (currentDraft == latestDraft) break
                latestDraft = currentDraft
            }

            if (latestDraft != appliedSettings) {
                onApplyTheme(appliedSettings, latestDraft)
            } else {
                onBack()
            }
            isClosing = false
        }
    }

    BackHandler(onBack = { requestBack() })

    MaterialExpressiveTheme(colorScheme = previewColorScheme, typography = typography) {
        key(draft, previewIsDark) {
            Scaffold(
                modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    MediumFlexibleTopAppBar(
                        title = { Text("主题") },
                        navigationIcon = {
                            IconButton(onClick = { requestBack() }, enabled = !isClosing) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                )
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
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    CollapsedSpecimen(settings = draft, isDark = previewIsDark)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                    ) {
                        ThemeSectionLabel("主题色")
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
                                    selected = !draft.useWallpaper &&
                                            !draft.useCustom &&
                                            draft.seed == seed,
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

                        InkleafActionListItem(
                            headline = "调色参数",
                            supporting = "${draft.customStyle.label} · ${draft.contrast.label}",
                            onClick = { showAdvancedColorSheet = true },
                            trailingContent = {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                            },
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ListItem(
                                onClick = {
                                    draft = draft.copy(useWallpaper = !draft.useWallpaper)
                                },
                                supportingContent = { Text("跟随壁纸色彩自动生成") },
                                trailingContent = {
                                    Switch(checked = draft.useWallpaper, onCheckedChange = null)
                                },
                            ) { Text("动态配色") }
                        }

                        ListItem(
                            onClick = { draft = draft.copy(useAmoled = !draft.useAmoled) },
                            supportingContent = { Text("深色模式下使用纯黑背景") },
                            trailingContent = {
                                Switch(checked = draft.useAmoled, onCheckedChange = null)
                            },
                        ) { Text("AMOLED 模式") }

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
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index,
                                        DarkMode.entries.size,
                                    ),
                                ) {
                                    Text(mode.label)
                                }
                            }
                        }

                        ThemeSectionLabel("配色方案")
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
                    }
                }
            }
        }

        if (showCustomColorSheet) {
            val customColorSheetState = rememberExpandOnlySheetState()
            ModalBottomSheet(
                onDismissRequest = { showCustomColorSheet = false },
                sheetState = customColorSheetState,
            ) {
                CustomColorSheetContent(
                    customArgb = draft.customArgb,
                    onPickColor = { argb ->
                        // Close before changing the preview theme so the stable input Dialog never
                        // contains Material components transitioning between two color schemes.
                        showCustomColorSheet = false
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
            val advancedColorSheetState = rememberExpandOnlySheetState()
            ModalBottomSheet(
                onDismissRequest = { showAdvancedColorSheet = false },
                sheetState = advancedColorSheetState,
            ) {
                key(draft, previewIsDark) {
                    AdvancedColorSheetContent(
                        settings = draft,
                        scrollState = advancedSheetScrollState,
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
        }
    }
}

private fun previewDescription(settings: ThemeSettings, isDark: Boolean): String {
    val source = when {
        settings.useWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "动态配色"
        settings.useCustom -> "自定义色"
        else -> settings.seed.label
    }
    val appearance = if (isDark) "深色" else "浅色"
    return "$source · $appearance · ${settings.customStyle.label} · ${settings.contrast.label}"
}

@Composable
private fun PreviewSystemBars(isDark: Boolean, appliedIsDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    fun setAppearance(dark: Boolean) {
        val window = (view.context as? Activity)?.window ?: return
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    SideEffect {
        setAppearance(isDark)
    }
    DisposableEffect(view, appliedIsDark) {
        onDispose {
            setAppearance(appliedIsDark)
        }
    }
}

@Composable
private fun CollapsedSpecimen(settings: ThemeSettings, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val colors = MaterialTheme.colorScheme
        listOf(
            colors.primary,
            colors.secondary,
            colors.tertiary,
            colors.surfaceContainerHighest,
            colors.error,
        ).forEach { color ->
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = previewDescription(settings, isDark),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun AdvancedColorSheetContent(
    settings: ThemeSettings,
    scrollState: ScrollState,
    onPickStyle: (CustomStyle) -> Unit,
    onPickContrast: (ThemeContrast) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetColumn(
        modifier = modifier,
        scrollable = true,
        scrollState = scrollState,
        selectable = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "调色参数",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onReset) {
                Icon(Icons.Filled.Refresh, contentDescription = "恢复推荐值")
            }
        }
        if (settings.useWallpaper) {
            InkleafInfoListItem(
                headline = "动态配色正在生效",
                supporting = "高级参数会保留，并在切回种子色时生效",
            )
        }
        ThemeSectionLabel("风格")
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
private const val THEME_AUTOSAVE_DEBOUNCE_MS = 400L

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
