package com.exio.inkleaf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.exio.inkleaf.data.AddComicOutcome
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.ReaderCache
import com.exio.inkleaf.data.ThemeSettings
import com.exio.inkleaf.data.ThemeSettingsRepository
import com.exio.inkleaf.ui.FavoriteViewerScreen
import com.exio.inkleaf.ui.FavoritesScreen
import com.exio.inkleaf.ui.ReaderScreen
import com.exio.inkleaf.ui.SettingsScreen
import com.exio.inkleaf.ui.ShelfScreen
import com.exio.inkleaf.ui.theme.InkleafTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 类型安全路由：路由就是普通数据类（类比 react-router 的 path + params，
 * 但参数有编译期类型保障）。@Serializable 让编译器生成参数的编解码器。
 */
@Serializable
data object ShelfRoute

@Serializable
data class ReaderRoute(val comicId: Long, val initialPage: Int? = null)

@Serializable
data object FavoritesRoute

@Serializable
data class FavoriteViewerRoute(val favoriteId: Long)

@Serializable
data object SettingsRoute

/** 页面转场时长。全宽滑动的运动量大，350~450ms 区间体感比较合适 */
private const val NAV_TRANSITION_MS = 400
private const val FAVORITES_RESULT_MESSAGE_KEY = "favorites_result_message"

private data class ExternalOpenRequest(val id: Long, val uri: Uri)

/**
 * M3 emphasized 缓动：起步快、减速段长，比默认 FastOutSlowIn 的收尾
 * 更舒展——同样时长下动画"可见的部分"更多，不会显得一闪而过
 */
private val NavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun <T> navSpec() = tween<T>(NAV_TRANSITION_MS, easing = NavEasing)

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
    currentDestination: NavDestination?,
    onOpenShelf: () -> Unit,
    onSelectFavorites: () -> Unit,
) {
    val shelfSelected = currentDestination?.hasRoute<ShelfRoute>() == true
    val favoritesSelected = currentDestination?.hasRoute<FavoritesRoute>() == true
    if (!shelfSelected && !favoritesSelected) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
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
            CompactBottomBarPlaceholder(iconRes = R.drawable.ic_folder)
            CompactBottomBarItem(
                selected = favoritesSelected,
                onClick = {
                    if (!favoritesSelected) onSelectFavorites()
                },
            ) { tint ->
                Icon(
                    painter = painterResource(
                        if (favoritesSelected) {
                            R.drawable.ic_favorite
                        } else {
                            R.drawable.ic_favorite_border
                        }
                    ),
                    contentDescription = "收藏",
                    tint = tint,
                )
            }
            CompactBottomBarPlaceholder(iconRes = R.drawable.ic_tune)
        }
    }
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
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
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
        modifier = Modifier
            .weight(1f)
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
        )
    }
}

class MainActivity : ComponentActivity() {
    private var externalOpenSequence = 0L
    private var externalOpenRequest by mutableStateOf<ExternalOpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用：它要接管系统启动画面（API 31+）
        // 或自己换主题（低版本），晚了启动画面就脱离控制了
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            queueExternalOpen(intent)
        }

        // 只在冷启动时清理：旧版固定名副本 + 上次进程被杀留下的过期半成品。
        // 旋转屏幕也会重新走 onCreate（此时 savedInstanceState != null），
        // 而存活的 ReaderViewModel 可能正持有缓存文件——不能清。
        // 注意按书持久化的副本是有意保留的，这里不会动它们
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                ReaderCache.cleanupOnColdStart(this@MainActivity)
            }
        }

        val themeRepo = ThemeSettingsRepository(this)
        // 异步读用户主题，启动画面保持到读取完成（null = 还没读到）。
        // 之前这里是 runBlocking 阻塞主线程换"首帧即用户主题"；现在启动
        // 画面盖住了首帧之前的空窗，同样不闪默认色，但主线程零阻塞
        var initialTheme by mutableStateOf<ThemeSettings?>(null)
        lifecycleScope.launch { initialTheme = themeRepo.settings.first() }

        // 同理预热书架首查：Room 单例在 splash 期间就打开数据库并跑完
        // 首次查询，ShelfScreen 组合后自己的收集几乎立刻命中，
        // "留白 → 网格"的切换被藏在启动画面背后。
        // first() 对空表也会发射 emptyList，不存在挂死 splash 的风险
        var shelfWarm by mutableStateOf(false)
        lifecycleScope.launch {
            ComicRepository(this@MainActivity).observeAll().first()
            shelfWarm = true
        }
        splashScreen.setKeepOnScreenCondition { initialTheme == null || !shelfWarm }

        setContent {
            // 主题没读到前不组合内容：此时启动画面还在屏上，用户看不到空窗
            val startTheme = initialTheme ?: return@setContent
            // 主题状态活在 NavHost 之上（影响所有页面，与导航平级）：
            // 设置页写入 DataStore → 这条 Flow 发新值 → 全 App 同帧换色
            val themeSettings by themeRepo.settings
                .collectAsStateWithLifecycle(initialValue = startTheme)

            InkleafTheme(settings = themeSettings) {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                val pendingExternalOpen = externalOpenRequest

                LaunchedEffect(pendingExternalOpen) {
                    val request = pendingExternalOpen ?: return@LaunchedEffect
                    val comicId = runCatching {
                        when (val outcome = ComicRepository(this@MainActivity)
                            .addOrGetComic(request.uri)) {
                            is AddComicOutcome.Added -> outcome.comic.id
                            is AddComicOutcome.AlreadyInLibrary -> outcome.comic.id
                            is AddComicOutcome.Restored -> outcome.comic.id
                        }
                    }.getOrElse {
                        Toast.makeText(
                            this@MainActivity,
                            "无法打开该漫画文件",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@LaunchedEffect
                    }
                    navController.navigate(ReaderRoute(comicId)) {
                        launchSingleTop = true
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 全局转场：全宽水平推入——前进时新页从右侧整页滑入，
                    // 旧页同步整页滑出，两页边贴边像传送带一样移过屏幕；
                    // 返回时方向相反。刻意不加 fade：两页全程淡化会把滑动
                    // 藏在透明度底下，看起来仍像交叉淡化（缺乏滑动感）。
                    // 两页位移量相同、无重叠区，因此不依赖绘制层级，任意
                    // 两页之间都成立：新增页面无需再配转场。
                    // slideInto/OutOfContainer 按布局方向解析 Start/End，RTL 自适应
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0),
                        bottomBar = {
                            InkleafBottomBar(
                                currentDestination = currentDestination,
                                onOpenShelf = { navController.navigateTopLevel(ShelfRoute) },
                                onSelectFavorites = {
                                    navController.navigateTopLevel(FavoritesRoute)
                                },
                            )
                        },
                    ) { outerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = ShelfRoute,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(outerPadding),
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
                            composable<ShelfRoute> {
                                ShelfScreen(
                                    onOpenComic = { id -> navController.navigate(ReaderRoute(id)) },
                                    onOpenSettings = { navController.navigate(SettingsRoute) },
                                )
                            }
                            composable<ReaderRoute> { entry ->
                                val route = entry.toRoute<ReaderRoute>()
                                ReaderScreen(
                                    comicId = route.comicId,
                                    initialPage = route.initialPage,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<FavoritesRoute> { entry ->
                                val viewerMessage by entry.savedStateHandle
                                    .getStateFlow<String?>(FAVORITES_RESULT_MESSAGE_KEY, null)
                                    .collectAsStateWithLifecycle()
                                FavoritesScreen(
                                    onOpenFavorite = { id ->
                                        navController.navigate(FavoriteViewerRoute(id))
                                    },
                                    viewerMessage = viewerMessage,
                                    onViewerMessageConsumed = {
                                        entry.savedStateHandle.set<String?>(
                                            FAVORITES_RESULT_MESSAGE_KEY,
                                            null,
                                        )
                                    },
                                )
                            }
                            composable<FavoriteViewerRoute> { entry ->
                                val route = entry.toRoute<FavoriteViewerRoute>()
                                FavoriteViewerScreen(
                                    favoriteId = route.favoriteId,
                                    onBack = { navController.popBackStack() },
                                    onOpenComicPage = { id, page ->
                                        navController.navigate(ReaderRoute(id, page))
                                    },
                                    onExitWithMessage = { message ->
                                        navController.previousBackStackEntry
                                            ?.savedStateHandle
                                            ?.set(FAVORITES_RESULT_MESSAGE_KEY, message)
                                        navController.popBackStack()
                                    },
                                )
                            }
                            composable<SettingsRoute> {
                                SettingsScreen(
                                    // 复用顶层已收集的主题状态：进设置页首帧即真实值，
                                    // 不会出现开关从默认值滑到真实值的突变
                                    themeSettings = themeSettings,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
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

    private fun queueExternalOpen(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        externalOpenRequest = ExternalOpenRequest(++externalOpenSequence, uri)
    }
}
