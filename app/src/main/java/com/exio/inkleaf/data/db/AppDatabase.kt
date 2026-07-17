package com.exio.inkleaf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** App database. Additive schema changes preserve local reading data when practical. */
@Database(
    entities = [
        ComicEntity::class,
        ChapterEntity::class,
        LibraryFolderEntity::class,
        FavoritePageEntity::class,
        ComicGroupEntity::class,
        AlbumPageEntity::class,
        EnhancementCacheTaskEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun chapterDao(): ChapterDao
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun comicGroupDao(): ComicGroupDao
    abstract fun albumPageDao(): AlbumPageDao
    abstract fun enhancementCacheTaskDao(): EnhancementCacheTaskDao

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
                    .addMigrations(MIGRATION_8_9)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS enhancement_cache_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        comicId INTEGER NOT NULL,
                        modelId TEXT NOT NULL,
                        modelRevision TEXT NOT NULL,
                        sourceRevision TEXT NOT NULL,
                        startPageInclusive INTEGER NOT NULL,
                        endPageInclusive INTEGER NOT NULL,
                        nextPage INTEGER NOT NULL,
                        completedPages INTEGER NOT NULL,
                        totalPages INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastError TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_enhancement_cache_tasks_comicId " +
                            "ON enhancement_cache_tasks(comicId)"
                )
            }
        }
    }
}
