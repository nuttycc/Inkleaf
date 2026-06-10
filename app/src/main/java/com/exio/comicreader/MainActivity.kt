package com.exio.comicreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.exio.comicreader.data.ComicBook
import com.exio.comicreader.ui.FoldersScreen
import com.exio.comicreader.ui.ReaderScreen
import com.exio.comicreader.ui.ShelfScreen
import com.exio.comicreader.ui.theme.ComicReaderTheme
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

        setContent {
            ComicReaderTheme {
                val navController = rememberNavController()

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
                            onOpenFolders = { navController.navigate(FoldersRoute) },
                        )
                    }
                    composable<ReaderRoute> { entry ->
                        val route = entry.toRoute<ReaderRoute>()
                        ReaderScreen(
                            comicId = route.comicId,
                            onBack = { navController.popBackStack() },
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
