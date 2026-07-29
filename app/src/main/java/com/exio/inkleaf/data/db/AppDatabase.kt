package com.exio.inkleaf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** App database. Version changes intentionally rebuild local data for this unreleased app. */
@Database(
    entities =
        [
            ComicEntity::class,
            ChapterEntity::class,
            LibraryFolderEntity::class,
            LibraryExclusionEntity::class,
            FavoritePageEntity::class,
            ComicGroupEntity::class,
            AlbumPageEntity::class,
            BookmarkEntity::class,
            ReadingSessionEntity::class,
        ],
    // v13+: reading activity history schema. No data migration — destructive rebuild is intentional
    // (#15).
    // v17 persists comics explicitly excluded from directory scans.
    version = 17,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao

    abstract fun chapterDao(): ChapterDao

    abstract fun libraryFolderDao(): LibraryFolderDao

    abstract fun libraryExclusionDao(): LibraryExclusionDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun comicGroupDao(): ComicGroupDao

    abstract fun albumPageDao(): AlbumPageDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
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
