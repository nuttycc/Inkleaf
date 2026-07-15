package com.exio.inkleaf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 数据库入口（单例模板见下方注释）。
 *
 * 当前仍是内部开发阶段：schema 调整直接重建本地库，避免把临时模型的
 * 迁移包袱带进主逻辑。发布给真实用户前再恢复显式 Migration。
 */
@Database(
    entities = [
        ComicEntity::class,
        ChapterEntity::class,
        LibraryFolderEntity::class,
        FavoritePageEntity::class,
        ComicGroupEntity::class,
        AlbumPageEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun chapterDao(): ChapterDao
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun comicGroupDao(): ComicGroupDao
    abstract fun albumPageDao(): AlbumPageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    // 必须 applicationContext：数据库活得比任何页面都久，
                    // 持有 Activity 的 context 会造成内存泄漏
                    context.applicationContext,
                    AppDatabase::class.java,
                    "comic_reader.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
