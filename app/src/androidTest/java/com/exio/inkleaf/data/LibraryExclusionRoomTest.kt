package com.exio.inkleaf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.LibraryExclusionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory Room checks for persisted library scan exclusions. */
@RunWith(AndroidJUnit4::class)
class LibraryExclusionRoomTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun exclusion_canBeQueriedAndClearedByFileKey() = runBlocking {
        val dao = db.libraryExclusionDao()
        dao.upsert(LibraryExclusionEntity(fileKey = "removed.cbz", excludedAt = 1L))

        assertEquals(
            listOf("removed.cbz"),
            dao.getExcludedFileKeys(listOf("removed.cbz", "new.cbz")),
        )

        dao.deleteByFileKey("removed.cbz")
        assertEquals(emptyList<String>(), dao.getExcludedFileKeys(listOf("removed.cbz")))
    }
}
