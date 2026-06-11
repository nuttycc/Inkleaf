package com.exio.comicreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.exio.comicreader.data.ReaderCache
import com.exio.comicreader.data.ThemeSettings
import com.exio.comicreader.data.ThemeSettingsRepository
import com.exio.comicreader.ui.ReaderScreen
import com.exio.comicreader.ui.SettingsScreen
import com.exio.comicreader.ui.ShelfScreen
import com.exio.comicreader.ui.theme.ComicReaderTheme
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
data class ReaderRoute(val comicId: Long)

@Serializable
data object SettingsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用：它要接管系统启动画面（API 31+）
        // 或自己换主题（低版本），晚了启动画面就脱离控制了
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        splashScreen.setKeepOnScreenCondition { initialTheme == null }

        setContent {
            // 主题没读到前不组合内容：此时启动画面还在屏上，用户看不到空窗
            val startTheme = initialTheme ?: return@setContent
            // 主题状态活在 NavHost 之上（影响所有页面，与导航平级）：
            // 设置页写入 DataStore → 这条 Flow 发新值 → 全 App 同帧换色
            val themeSettings by themeRepo.settings
                .collectAsStateWithLifecycle(initialValue = startTheme)

            ComicReaderTheme(settings = themeSettings) {
                val navController = rememberNavController()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Navigation 默认转场是 700ms 交叉淡化，偏慢（黑色阅读页
                    // 淡出时残影明显）；统一换成 250ms
                    NavHost(
                        navController = navController,
                        startDestination = ShelfRoute,
                        enterTransition = { fadeIn(tween(250)) },
                        exitTransition = { fadeOut(tween(250)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { fadeOut(tween(250)) },
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
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
