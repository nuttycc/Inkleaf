package com.exio.comicreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库入口（单例模板见下方注释）。
 *
 * 关于版本与迁移：改了 Entity 结构就必须 version+1 并提供 Migration，
 * 告诉 Room 怎么把用户手机上的旧表改成新结构——否则已安装用户升级后
 * 数据库打不开直接崩溃。Room 在迁移后会校验实际表结构与 Entity 期望
 * 是否一致，SQL 写错会抛 "Migration didn't properly handle: xxx"，
 * 错误信息里列出期望/实际两份 schema，照着对差异即可修复。
 */
@Database(
    entities = [ComicEntity::class, LibraryFolderEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun libraryFolderDao(): LibraryFolderDao

    companion object {
        /** v1 → v2：comics 加两列 + 新建 library_folders 表 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 可空列默认 NULL 即可
                db.execSQL("ALTER TABLE comics ADD COLUMN folderId INTEGER DEFAULT NULL")
                // Kotlin Boolean 在 SQLite 里是 INTEGER；非空字段必须
                // NOT NULL + DEFAULT，否则与 Room 期望的 schema 不一致
                db.execSQL("ALTER TABLE comics ADD COLUMN isMissing INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS library_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        treeUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )"""
                )
                // 索引名必须符合 Room 的命名格式 index_表名_列名，否则校验失败
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_library_folders_treeUri " +
                        "ON library_folders(treeUri)"
                )
            }
        }

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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
