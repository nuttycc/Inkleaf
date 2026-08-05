package com.exio.inkleaf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.ThemeSettingsRepository
import com.exio.inkleaf.diagnostics.DiagnosticRepository
import com.exio.inkleaf.plugin.PluginContentCodec
import com.exio.inkleaf.ui.AlbumEditorScreen
import com.exio.inkleaf.ui.DiagnosticScreen
import com.exio.inkleaf.ui.FavoriteViewerScreen
import com.exio.inkleaf.ui.HistoryScreen
import com.exio.inkleaf.ui.OnlineComicScreen
import com.exio.inkleaf.ui.OnlineReaderScreen
import com.exio.inkleaf.ui.OnlineReaderTarget
import com.exio.inkleaf.ui.PluginDiscoverScreen
import com.exio.inkleaf.ui.ReaderScreen
import com.exio.inkleaf.ui.SavedScreen
import com.exio.inkleaf.ui.SettingsScreen
import com.exio.inkleaf.ui.ShelfScreen
import com.exio.inkleaf.ui.SourceDetailScreen
import com.exio.inkleaf.ui.SourcesScreen
import com.exio.inkleaf.ui.ThemeSettingsScreen
import com.exio.inkleaf.ui.theme.InkleafTheme
import com.exio.inkleaf.ui.toRouteSeed
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import timber.log.Timber

/** 类型安全路由：路由就是普通数据类（类比 react-router 的 path + params， 但参数有编译期类型保障）。@Serializable 让编译器生成参数的编解码器。 */
@Serializable data object ShellRoute

@Serializable data object ShelfRoute

@Serializable data class ReaderRoute(val comicId: Long, val initialPage: Int? = null)

@Serializable data class AlbumEditorRoute(val comicId: Long? = null)

@Serializable data object HistoryRoute

@Serializable data object FavoritesRoute

@Serializable data class FavoriteViewerRoute(val favoriteId: Long)

@Serializable data object SettingsRoute

@Serializable data object DiagnosticRoute

@Serializable data object ThemeSettingsRoute

@Serializable data object PluginDiscoverRoute

@Serializable data object SourcesRoute

@Serializable data class SourceDetailRoute(val pluginId: String)

@Serializable
data class OnlineComicRoute(
    val pluginId: String,
    val sourceId: String,
    val opaqueContextJson: String? = null,
    val summaryJson: String? = null,
)

@Serializable
data class OnlineReaderRoute(
    val pluginId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterRevision: String? = null,
    val opaqueContextJson: String? = null,
    val initialPageId: String? = null,
    val initialPageIndex: Int? = null,
)

private fun OnlineReaderTarget.toRoute(): OnlineReaderRoute =
    OnlineReaderRoute(
        pluginId = pluginId,
        sourceId = sourceId,
        chapterId = chapterId,
        chapterRevision = chapterRevision,
        opaqueContextJson = opaqueContextJson,
        initialPageId = initialPageId,
        initialPageIndex = initialPageIndex,
    )

/** 外层壳↔二级：全宽滑动的运动量大，350~450ms 区间体感比较合适 */
private const val NAV_TRANSITION_MS = 400

/** Tab↔Tab：同时交叉淡化，短于外层，避免和外层滑推抢同一套运动语言 */
private const val TAB_TRANSITION_MS = 200
private const val FAVORITES_RESULT_MESSAGE_KEY = "favorites_result_message"

private enum class TopLevelDestination {
    SHELF,
    HISTORY,
    FAVORITES,
    DISCOVER,
}

private data class ExternalOpenRequest(val id: Long, val uri: Uri)

/** M3 emphasized 缓动：起步快、减速段长，比默认 FastOutSlowIn 的收尾 更舒展——同样时长下动画"可见的部分"更多，不会显得一闪而过 */
private val NavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun <T> navSpec() = tween<T>(NAV_TRANSITION_MS, easing = NavEasing)

private fun <T> tabNavSpec() = tween<T>(TAB_TRANSITION_MS)

private fun <T : Any> NavHostController.navigateTopLevel(route: T) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun InkleafBottomBar(
    selectedDestination: TopLevelDestination,
    onOpenShelf: () -> Unit,
    onOpenHistory: () -> Unit,
    onSelectFavorites: () -> Unit,
    onOpenDiscover: () -> Unit,
) {
    val shelfSelected = selectedDestination == TopLevelDestination.SHELF
    val historySelected = selectedDestination == TopLevelDestination.HISTORY
    val favoritesSelected = selectedDestination == TopLevelDestination.FAVORITES
    val discoverSelected = selectedDestination == TopLevelDestination.DISCOVER

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactBottomBarItem(
                selected = shelfSelected,
                onClick = {
                    if (!shelfSelected) onOpenShelf()
                },
            ) { tint ->
                Icon(
                    Icons.Filled.Home,
                    contentDescription = "书架",
                    tint = tint,
                )
            }
            CompactBottomBarItem(
                selected = historySelected,
                onClick = {
                    if (!historySelected) onOpenHistory()
                },
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    contentDescription = "历史",
                    tint = tint,
                )
            }
            CompactBottomBarItem(
                selected = favoritesSelected,
                onClick = {
                    if (!favoritesSelected) onSelectFavorites()
                },
            ) { tint ->
                Icon(
                    painter =
                        painterResource(
                            if (favoritesSelected) {
                                R.drawable.ic_favorite
                            } else {
                                R.drawable.ic_favorite_border
                            }
                        ),
                    contentDescription = "已保存",
                    tint = tint,
                )
            }
            CompactBottomBarItem(
                selected = discoverSelected,
                onClick = {
                    if (!discoverSelected) onOpenDiscover()
                },
            ) { tint ->
                Icon(
                    painter =
                        painterResource(
                            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_extension_outlined
                        ),
                    contentDescription = "发现",
                    tint = tint,
                )
            }
        }
    }
}

@Composable
private fun TopLevelScaffold(
    selectedDestination: TopLevelDestination,
    onOpenShelf: () -> Unit,
    onOpenHistory: () -> Unit,
    onSelectFavorites: () -> Unit,
    onOpenDiscover: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            InkleafBottomBar(
                selectedDestination = selectedDestination,
                onOpenShelf = onOpenShelf,
                onOpenHistory = onOpenHistory,
                onSelectFavorites = onSelectFavorites,
                onOpenDiscover = onOpenDiscover,
            )
        },
        content = content,
    )
}

@Composable
private fun RowScope.CompactBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    val indicatorColor = if (selected) colors.secondaryContainer else Color.Transparent

    Box(
        modifier =
            Modifier.weight(1f)
                .height(48.dp)
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.Tab,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(width = 56.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            contentAlignment = Alignment.Center,
        ) {
            icon(tint)
        }
    }
}

@Composable
private fun RowScope.CompactBottomBarPlaceholder(iconRes: Int) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)

    Box(
        modifier = Modifier.weight(1f).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
        )
    }
}

class MainActivity : AppCompatActivity() {
    private var externalOpenSequence = 0L
    private var externalOpenRequest by mutableStateOf<ExternalOpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用：它要接管系统启动画面（API 31+）
        // 或自己换主题（低版本），晚了启动画面就脱离控制了
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        externalOpenSequence = savedInstanceState?.getLong(STATE_EXTERNAL_OPEN_SEQUENCE) ?: 0L
        val restoredExternalUri =
            savedInstanceState?.getString(STATE_EXTERNAL_OPEN_URI)?.let(Uri::parse)
        if (restoredExternalUri != null) {
            externalOpenRequest =
                ExternalOpenRequest(
                    id = savedInstanceState.getLong(STATE_EXTERNAL_OPEN_ID),
                    uri = restoredExternalUri,
                )
        } else if (savedInstanceState == null) {
            queueExternalOpen(intent)
        }

        val themeRepo = ThemeSettingsRepository(this)
        // 异步读用户主题，启动画面保持到读取完成（null = 还没读到）。
        // 之前这里是 runBlocking 阻塞主线程换"首帧即用户主题"；现在启动
        // 画面盖住了首帧之前的空窗，同样不闪默认色，但主线程零阻塞
        var initialTheme by mutableStateOf<ThemeSettings?>(null)
        lifecycleScope.launch {
            val loadedTheme =
                try {
                    withTimeout(THEME_LOAD_TIMEOUT_MS.milliseconds) { themeRepo.settings.first() }
                } catch (error: TimeoutCancellationException) {
                    Timber.e(error, "Timed out while loading theme settings")
                    ThemeSettings()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.e(error, "Failed to load theme settings")
                    ThemeSettings()
                }
            val awaitingRecreation =
                ThemeNightModeController.synchronizeStartup(
                    activity = this@MainActivity,
                    darkMode = loadedTheme.darkMode,
                )
            if (!awaitingRecreation) {
                initialTheme = loadedTheme
            }
        }

        // Shelf warmup hides the initial empty-to-grid transition, but it is best-effort and may
        // never keep the splash screen longer than the bounded startup budget.
        var shelfWarm by mutableStateOf(false)
        lifecycleScope.launch {
            try {
                withTimeout(SHELF_WARMUP_TIMEOUT_MS.milliseconds) {
                    (application as InkleafApplication).awaitShelfWarmup()
                }
            } catch (error: TimeoutCancellationException) {
                Timber.w(error, "Shelf warmup timed out; continuing startup")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Shelf warmup failed; continuing startup")
            } finally {
                shelfWarm = true
            }
        }
        splashScreen.setKeepOnScreenCondition { initialTheme == null || !shelfWarm }

        setContent {
            // 主题没读到前不组合内容：此时启动画面还在屏上，用户看不到空窗
            // Freeze theme settings for this Activity instance. A committed theme is only observed
            // by the recreated Activity, so components never animate from an old global scheme.
            val themeSettings = initialTheme ?: return@setContent

            InkleafTheme(settings = themeSettings) {
                // 外层：壳 ↔ 二级；内层 Tab NavController 建在 Shell 目的地里
                val outerNavController = rememberNavController()
                val pendingExternalOpen = externalOpenRequest
                val diagnostics = remember { DiagnosticRepository.get(this@MainActivity) }
                val unreadCriticalDiagnostics by diagnostics.unreadCriticalCount.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(unreadCriticalDiagnostics) {
                    if (unreadCriticalDiagnostics <= 0) return@LaunchedEffect
                    val result =
                        snackbarHostState.showSnackbar(
                            message = "发现 $unreadCriticalDiagnostics 条新的崩溃或异常退出",
                            actionLabel = "查看",
                            withDismissAction = true,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        outerNavController.navigate(DiagnosticRoute) { launchSingleTop = true }
                    }
                }

                LaunchedEffect(pendingExternalOpen) {
                    val request = pendingExternalOpen ?: return@LaunchedEffect
                    val comicId =
                        try {
                            ComicRepository(this@MainActivity).addOrGetComic(request.uri).comic.id
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Timber.w(error, "Failed to open external comic")
                            if (consumeExternalOpenRequest(request)) {
                                Toast.makeText(
                                        this@MainActivity,
                                        "无法打开该漫画文件",
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                            return@LaunchedEffect
                        }
                    if (!consumeExternalOpenRequest(request)) return@LaunchedEffect
                    outerNavController.navigate(ReaderRoute(comicId)) {
                        launchSingleTop = true
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    // 外层转场：全宽水平推入——前进时新页从右侧整页滑入，
                    // 旧页同步整页滑出；返回相反。刻意不加 fade。
                    // 底栏在壳内、内层 Tab NavHost 外，Tab 切换时不参与此外层动画；
                    // 进二级时整壳（含底栏）一起滑走。
                    NavHost(
                        navController = outerNavController,
                        startDestination = ShellRoute,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = {
                            slideIntoContainer(SlideDirection.Start, navSpec())
                        },
                        exitTransition = {
                            slideOutOfContainer(SlideDirection.Start, navSpec())
                        },
                        popEnterTransition = {
                            slideIntoContainer(SlideDirection.End, navSpec())
                        },
                        popExitTransition = {
                            slideOutOfContainer(SlideDirection.End, navSpec())
                        },
                    ) {
                        composable<ShellRoute> { shellEntry ->
                            val innerNavController = rememberNavController()
                            val innerBackStackEntry by
                                innerNavController.currentBackStackEntryAsState()
                            val innerDestination = innerBackStackEntry?.destination
                            val selectedDestination =
                                when {
                                    innerDestination?.hasRoute<HistoryRoute>() == true ->
                                        TopLevelDestination.HISTORY
                                    innerDestination?.hasRoute<FavoritesRoute>() == true ->
                                        TopLevelDestination.FAVORITES
                                    innerDestination?.hasRoute<PluginDiscoverRoute>() == true ->
                                        TopLevelDestination.DISCOVER
                                    else -> TopLevelDestination.SHELF
                                }
                            // 收藏查看结果写在外层 Shell entry 上，再桥进内层已保存页
                            val viewerMessage by
                                shellEntry.savedStateHandle
                                    .getStateFlow<String?>(FAVORITES_RESULT_MESSAGE_KEY, null)
                                    .collectAsStateWithLifecycle()

                            TopLevelScaffold(
                                selectedDestination = selectedDestination,
                                onOpenShelf = {
                                    innerNavController.navigateTopLevel(ShelfRoute)
                                },
                                onOpenHistory = {
                                    innerNavController.navigateTopLevel(HistoryRoute)
                                },
                                onSelectFavorites = {
                                    innerNavController.navigateTopLevel(FavoritesRoute)
                                },
                                onOpenDiscover = {
                                    innerNavController.navigateTopLevel(PluginDiscoverRoute)
                                },
                            ) { topLevelPadding ->
                                NavHost(
                                    navController = innerNavController,
                                    startDestination = ShelfRoute,
                                    modifier = Modifier.fillMaxSize().padding(topLevelPadding),
                                    enterTransition = { fadeIn(tabNavSpec()) },
                                    exitTransition = { fadeOut(tabNavSpec()) },
                                    popEnterTransition = { fadeIn(tabNavSpec()) },
                                    popExitTransition = { fadeOut(tabNavSpec()) },
                                ) {
                                    composable<ShelfRoute> {
                                        ShelfScreen(
                                            onOpenComic = { id ->
                                                outerNavController.navigate(ReaderRoute(id))
                                            },
                                            onOpenOnlineComic = { record ->
                                                outerNavController.navigate(
                                                    OnlineComicRoute(
                                                        pluginId = record.key.pluginId,
                                                        sourceId = record.key.sourceId,
                                                        opaqueContextJson =
                                                            record.detail?.opaqueContext?.toString(),
                                                    )
                                                )
                                            },
                                            onCreateAlbum = {
                                                outerNavController.navigate(AlbumEditorRoute())
                                            },
                                            onEditAlbum = { id ->
                                                outerNavController.navigate(AlbumEditorRoute(id))
                                            },
                                            onOpenSettings = {
                                                outerNavController.navigate(SettingsRoute)
                                            },
                                        )
                                    }
                                    composable<HistoryRoute> {
                                        HistoryScreen(
                                            onOpenSession = { comicId, page ->
                                                outerNavController.navigate(
                                                    ReaderRoute(comicId, page)
                                                )
                                            },
                                            onOpenOnlineSession = { target ->
                                                outerNavController.navigate(target.toRoute())
                                            },
                                        )
                                    }
                                    composable<FavoritesRoute> {
                                        SavedScreen(
                                            onOpenBookmark = { comicId, globalPage ->
                                                outerNavController.navigate(
                                                    ReaderRoute(comicId, globalPage)
                                                )
                                            },
                                            onOpenOnlinePage = { target ->
                                                outerNavController.navigate(target.toRoute())
                                            },
                                            onOpenFavorite = { id ->
                                                outerNavController.navigate(FavoriteViewerRoute(id))
                                            },
                                            viewerMessage = viewerMessage,
                                            onViewerMessageConsumed = {
                                                shellEntry.savedStateHandle.set<String?>(
                                                    FAVORITES_RESULT_MESSAGE_KEY,
                                                    null,
                                                )
                                            },
                                        )
                                    }
                                    composable<PluginDiscoverRoute> {
                                        PluginDiscoverScreen(
                                            onOpenComic = { pluginId, comic ->
                                                outerNavController.navigate(
                                                    OnlineComicRoute(
                                                        pluginId = pluginId,
                                                        sourceId = comic.sourceId,
                                                        opaqueContextJson =
                                                            comic.opaqueContext?.toString(),
                                                        summaryJson =
                                                            PluginContentCodec.json.encodeToString(
                                                                comic.toRouteSeed()
                                                            ),
                                                    )
                                                )
                                            },
                                            onOpenSources = {
                                                outerNavController.navigate(SourcesRoute)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        composable<AlbumEditorRoute> { entry ->
                            val route = entry.toRoute<AlbumEditorRoute>()
                            AlbumEditorScreen(
                                comicId = route.comicId,
                                onBack = { outerNavController.popBackStack() },
                            )
                        }
                        composable<ReaderRoute> { entry ->
                            val route = entry.toRoute<ReaderRoute>()
                            ReaderScreen(
                                comicId = route.comicId,
                                initialPage = route.initialPage,
                                onBack = { outerNavController.popBackStack() },
                            )
                        }
                        composable<FavoriteViewerRoute> { entry ->
                            val route = entry.toRoute<FavoriteViewerRoute>()
                            FavoriteViewerScreen(
                                favoriteId = route.favoriteId,
                                onBack = { outerNavController.popBackStack() },
                                onOpenComicPage = { id, page ->
                                    outerNavController.navigate(ReaderRoute(id, page))
                                },
                                onExitWithMessage = { message ->
                                    outerNavController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set(FAVORITES_RESULT_MESSAGE_KEY, message)
                                    outerNavController.popBackStack()
                                },
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                themeSettings = themeSettings,
                                onBack = { outerNavController.popBackStack() },
                                onOpenThemeSettings = {
                                    outerNavController.navigate(ThemeSettingsRoute)
                                },
                                onOpenDiagnostics = {
                                    outerNavController.navigate(DiagnosticRoute)
                                },
                            )
                        }
                        composable<DiagnosticRoute> {
                            DiagnosticScreen(onBack = { outerNavController.popBackStack() })
                        }
                        composable<ThemeSettingsRoute> {
                            ThemeSettingsScreen(
                                appliedSettings = themeSettings,
                                onBack = { outerNavController.popBackStack() },
                                onApplyTheme = { applied, committed ->
                                    outerNavController.popBackStack()
                                    ThemeNightModeController.applyCommittedTheme(
                                        activity = this@MainActivity,
                                        applied = applied,
                                        committed = committed,
                                    )
                                },
                            )
                        }
                        composable<SourcesRoute> {
                            SourcesScreen(
                                onBack = { outerNavController.popBackStack() },
                                onOpenSource = { pluginId ->
                                    outerNavController.navigate(SourceDetailRoute(pluginId))
                                },
                            )
                        }
                        composable<SourceDetailRoute> { entry ->
                            val route = entry.toRoute<SourceDetailRoute>()
                            SourceDetailScreen(
                                pluginId = route.pluginId,
                                onBack = { outerNavController.popBackStack() },
                            )
                        }
                        composable<OnlineComicRoute> { entry ->
                            val route = entry.toRoute<OnlineComicRoute>()
                            OnlineComicScreen(
                                pluginId = route.pluginId,
                                sourceId = route.sourceId,
                                opaqueContextJson = route.opaqueContextJson,
                                summaryJson = route.summaryJson,
                                onBack = { outerNavController.popBackStack() },
                                onOpenChapter = { chapter, effectiveContext ->
                                    outerNavController.navigate(
                                        OnlineReaderRoute(
                                            pluginId = route.pluginId,
                                            sourceId = route.sourceId,
                                            chapterId = chapter.chapterId,
                                            chapterRevision = chapter.revision,
                                            opaqueContextJson = effectiveContext?.toString(),
                                        )
                                    )
                                },
                                onContinueReading = { position ->
                                    outerNavController.navigate(
                                        OnlineReaderRoute(
                                            pluginId = route.pluginId,
                                            sourceId = route.sourceId,
                                            chapterId = position.chapterId,
                                            chapterRevision = position.chapterRevision,
                                            initialPageId = position.pageId,
                                            initialPageIndex = position.pageIndex,
                                        )
                                    )
                                },
                            )
                        }
                        composable<OnlineReaderRoute> { entry ->
                            val route = entry.toRoute<OnlineReaderRoute>()
                            OnlineReaderScreen(
                                pluginId = route.pluginId,
                                sourceId = route.sourceId,
                                chapterId = route.chapterId,
                                chapterRevision = route.chapterRevision,
                                opaqueContextJson = route.opaqueContextJson,
                                initialPageId = route.initialPageId,
                                initialPageIndex = route.initialPageIndex,
                                onBack = { outerNavController.popBackStack() },
                            )
                        }
                    }
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueExternalOpen(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_EXTERNAL_OPEN_SEQUENCE, externalOpenSequence)
        externalOpenRequest?.let { request ->
            outState.putLong(STATE_EXTERNAL_OPEN_ID, request.id)
            outState.putString(STATE_EXTERNAL_OPEN_URI, request.uri.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun queueExternalOpen(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        externalOpenRequest = ExternalOpenRequest(++externalOpenSequence, uri)
    }

    private fun consumeExternalOpenRequest(request: ExternalOpenRequest): Boolean =
        if (externalOpenRequest?.id == request.id) {
            externalOpenRequest = null
            true
        } else {
            false
        }

    private companion object {
        const val THEME_LOAD_TIMEOUT_MS = 5_000L
        const val SHELF_WARMUP_TIMEOUT_MS = 3_000L
        const val STATE_EXTERNAL_OPEN_SEQUENCE = "external_open_sequence"
        const val STATE_EXTERNAL_OPEN_ID = "external_open_id"
        const val STATE_EXTERNAL_OPEN_URI = "external_open_uri"
    }
}
