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
        BookmarkEntity::class,
        ReadingSessionEntity::class,
    ],
    // v13+: reading activity history schema. No data migration — destructive rebuild is intentional (#15).
    // v16 removes the retired enhancement cache and selection column with an explicit data migration.
    version = 16,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun chapterDao(): ChapterDao
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun comicGroupDao(): ComicGroupDao
    abstract fun albumPageDao(): AlbumPageDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao

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
                    .addMigrations(
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_11_12,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                    )
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

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enhancement_cache_tasks ADD COLUMN activeSlot INTEGER")
                // Version 9 tracked only a contiguous cursor. Resetting task metadata avoids
                // inventing completion rows that could overstate durable out-of-order progress.
                db.execSQL("DELETE FROM enhancement_cache_tasks")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_enhancement_cache_tasks_activeSlot " +
                            "ON enhancement_cache_tasks(activeSlot)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS enhancement_cache_completed_pages (
                        taskId TEXT NOT NULL,
                        page INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL,
                        PRIMARY KEY(taskId, page),
                        FOREIGN KEY(taskId) REFERENCES enhancement_cache_tasks(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        comicId INTEGER NOT NULL,
                        targetKey TEXT NOT NULL,
                        pageIdentity TEXT,
                        sourceRevision TEXT NOT NULL,
                        globalPageIndex INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        FOREIGN KEY(comicId) REFERENCES comics(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmarks_comicId " +
                            "ON bookmarks(comicId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_comicId_targetKey " +
                            "ON bookmarks(comicId, targetKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmarks_addedAt " +
                            "ON bookmarks(addedAt)"
                )
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Legacy tasks keep pipelineRevision=1 so the worker expires them after the
                // preprocess/eligibility pipeline bump instead of treating old checkpoints as done.
                db.execSQL(
                    "ALTER TABLE enhancement_cache_tasks " +
                            "ADD COLUMN pipelineRevision TEXT NOT NULL DEFAULT '1'"
                )
                db.execSQL(
                    "ALTER TABLE enhancement_cache_completed_pages " +
                            "ADD COLUMN resultKind TEXT NOT NULL DEFAULT 'enhanced'"
                )
            }
        }

        /**
         * Remove the retired enhancement data without losing shelf or reading history.
         *
         * SQLite cannot drop a column on all Android versions supported by the app. Rebuilding
         * the parent table therefore also requires temporarily copying and recreating each child
         * table that has a foreign key to comics. The backup tables are private to this migration
         * and are removed before it commits.
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // AUTOINCREMENT promises never to reuse an ID, even after the highest row is
                // deleted. Preserve sqlite_sequence explicitly while the three tables are rebuilt.
                db.execSQL(
                    """
                    CREATE TABLE `__inkleaf_migration_15_16_sequence` AS
                    SELECT name, seq
                    FROM sqlite_sequence
                    WHERE name IN ('comics', 'chapters', 'bookmarks')
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE `__inkleaf_migration_15_16_chapters` AS
                    SELECT id, comicId, chapterIndex, uri, fileKey, title, relativePath,
                           mimeType, size, lastModified, pageCount, isMissing
                    FROM chapters
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE `__inkleaf_migration_15_16_album_pages` AS
                    SELECT id, comicId, position, relativePath, displayName, extension
                    FROM album_pages
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE `__inkleaf_migration_15_16_bookmarks` AS
                    SELECT id, comicId, targetKey, pageIdentity, sourceRevision,
                           globalPageIndex, chapterIndex, pageIndex, chapterTitle, addedAt
                    FROM bookmarks
                    """.trimIndent()
                )

                // Drop enhancement-only tables before replacing their comics parent.
                db.execSQL("DROP TABLE IF EXISTS `enhancement_cache_completed_pages`")
                db.execSQL("DROP TABLE IF EXISTS `enhancement_cache_tasks`")

                // Remove the foreign-key children so the old comics table can be replaced safely.
                db.execSQL("DROP TABLE `chapters`")
                db.execSQL("DROP TABLE `album_pages`")
                db.execSQL("DROP TABLE `bookmarks`")

                db.execSQL(
                    """
                    CREATE TABLE `comics_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uri` TEXT NOT NULL,
                        `fileKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `pageCount` INTEGER NOT NULL,
                        `lastReadChapterIndex` INTEGER NOT NULL,
                        `lastReadPage` INTEGER NOT NULL,
                        `coverPath` TEXT,
                        `addedAt` INTEGER NOT NULL,
                        `lastReadAt` INTEGER NOT NULL,
                        `folderId` INTEGER,
                        `groupId` INTEGER,
                        `isMissing` INTEGER NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `lastReadPageId` TEXT,
                        `coverPageId` TEXT,
                        `isDraft` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `comics_new` (
                        id, uri, fileKey, title, pageCount, lastReadChapterIndex, lastReadPage,
                        coverPath, addedAt, lastReadAt, folderId, groupId, isMissing, sourceType,
                        lastReadPageId, coverPageId, isDraft
                    )
                    SELECT id, uri, fileKey, title, pageCount, lastReadChapterIndex, lastReadPage,
                           coverPath, addedAt, lastReadAt, folderId, groupId, isMissing, sourceType,
                           lastReadPageId, coverPageId, isDraft
                    FROM `comics`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `comics`")
                db.execSQL("ALTER TABLE `comics_new` RENAME TO `comics`")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_comics_fileKey` ON `comics` (`fileKey`)"
                )
                db.execSQL("CREATE UNIQUE INDEX `index_comics_uri` ON `comics` (`uri`)")
                db.execSQL("CREATE INDEX `index_comics_groupId` ON `comics` (`groupId`)")

                db.execSQL(
                    """
                    CREATE TABLE `chapters` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `comicId` INTEGER NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `fileKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `mimeType` TEXT,
                        `size` INTEGER,
                        `lastModified` INTEGER,
                        `pageCount` INTEGER NOT NULL,
                        `isMissing` INTEGER NOT NULL,
                        FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `chapters`
                    SELECT id, comicId, chapterIndex, uri, fileKey, title, relativePath,
                           mimeType, size, lastModified, pageCount, isMissing
                    FROM `__inkleaf_migration_15_16_chapters`
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX `index_chapters_comicId_chapterIndex` " +
                            "ON `chapters` (`comicId`, `chapterIndex`)"
                )
                db.execSQL("CREATE INDEX `index_chapters_comicId` ON `chapters` (`comicId`)")
                db.execSQL("CREATE UNIQUE INDEX `index_chapters_fileKey` ON `chapters` (`fileKey`)")

                db.execSQL(
                    """
                    CREATE TABLE `album_pages` (
                        `id` TEXT NOT NULL,
                        `comicId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `extension` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `album_pages`
                    SELECT id, comicId, position, relativePath, displayName, extension
                    FROM `__inkleaf_migration_15_16_album_pages`
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX `index_album_pages_comicId` ON `album_pages` (`comicId`)")
                db.execSQL(
                    "CREATE INDEX `index_album_pages_comicId_position` " +
                            "ON `album_pages` (`comicId`, `position`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `comicId` INTEGER NOT NULL,
                        `targetKey` TEXT NOT NULL,
                        `pageIdentity` TEXT,
                        `sourceRevision` TEXT NOT NULL,
                        `globalPageIndex` INTEGER NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `chapterTitle` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `bookmarks`
                    SELECT id, comicId, targetKey, pageIdentity, sourceRevision,
                           globalPageIndex, chapterIndex, pageIndex, chapterTitle, addedAt
                    FROM `__inkleaf_migration_15_16_bookmarks`
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX `index_bookmarks_comicId` ON `bookmarks` (`comicId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_bookmarks_comicId_targetKey` " +
                            "ON `bookmarks` (`comicId`, `targetKey`)"
                )
                db.execSQL("CREATE INDEX `index_bookmarks_addedAt` ON `bookmarks` (`addedAt`)")

                db.execSQL("DROP TABLE `__inkleaf_migration_15_16_chapters`")
                db.execSQL("DROP TABLE `__inkleaf_migration_15_16_album_pages`")
                db.execSQL("DROP TABLE `__inkleaf_migration_15_16_bookmarks`")

                db.execSQL(
                    "DELETE FROM sqlite_sequence WHERE name IN ('comics', 'chapters', 'bookmarks')"
                )
                db.execSQL(
                    """
                    INSERT INTO sqlite_sequence(name, seq)
                    SELECT name, seq FROM `__inkleaf_migration_15_16_sequence`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `__inkleaf_migration_15_16_sequence`")

                val foreignKeyViolations = db.query("PRAGMA foreign_key_check")
                try {
                    check(!foreignKeyViolations.moveToFirst()) {
                        "15→16 database migration produced a foreign-key violation"
                    }
                } finally {
                    foreignKeyViolations.close()
                }
            }
        }
    }
}
