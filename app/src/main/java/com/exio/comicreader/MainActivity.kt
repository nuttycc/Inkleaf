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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.exio.comicreader.data.ComicBook
import com.exio.comicreader.data.ThemeSettingsRepository
import com.exio.comicreader.ui.FoldersScreen
import com.exio.comicreader.ui.ReaderScreen
import com.exio.comicreader.ui.SettingsScreen
import com.exio.comicreader.ui.ShelfScreen
import com.exio.comicreader.ui.theme.ComicReaderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 类型安全路由：路由就是普通数据类（类比 react-router 的 path + params，
 * 但参数有编译期类型保障）。@Serializable 让编译器生成参数的编解码器。
 */
@Serializable
data object ShelfRoute

@Serializable
data class ReaderRoute(val comicId: Long)

@Serializable
data object FoldersRoute

@Serializable
data object SettingsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 只在冷启动时清理残留的 zip 副本。旋转屏幕也会重新走 onCreate
        // （此时 savedInstanceState != null），而存活的 ReaderViewModel
        // 可能正持有这个文件——不能删
        if (savedInstanceState == null) {
            File(cacheDir, ComicBook.CACHE_FILE_NAME).delete()
        }

        val themeRepo = ThemeSettingsRepository(this)
        // 同步读一次主题作为初始值：DataStore 是异步 Flow，若用默认值起步，
        // 第一帧会先渲染默认主题、下一帧才换成用户主题（冷启动闪色）。
        // 首读是毫秒级小文件 IO，这是少数 runBlocking 合理的场景
        val initialTheme = runBlocking { themeRepo.settings.first() }

        setContent {
            // 主题状态活在 NavHost 之上（影响所有页面，与导航平级）：
            // 设置页写入 DataStore → 这条 Flow 发新值 → 全 App 同帧换色
            val themeSettings by themeRepo.settings
                .collectAsStateWithLifecycle(initialValue = initialTheme)

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
                                onOpenFolders = { navController.navigate(FoldersRoute) },
                            )
                        }
                        composable<FoldersRoute> {
                            FoldersScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
