package com.exio.inkleaf.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.ComicEntity
import com.exio.inkleaf.data.db.HistoryRowProjection
import com.exio.inkleaf.data.db.ReadingSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room checks for reading_sessions invariants.
 * Run via android-dev-check full (emulator), not on the local machine.
 */
@RunWith(AndroidJUnit4::class)
class ReadingSessionRoomTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun secondResumableInsert_failsUnlessFirstIsSettled() = runBlocking {
        val dao = db.readingSessionDao()
        dao.insert(resumable(id = "a", startedAt = 100))
        var failed = false
        try {
            dao.insert(resumable(id = "b", startedAt = 200))
        } catch (_: SQLiteConstraintException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals("a", dao.getResumable()?.id)

        // Explicit settle: delete resumable, insert completed, then new resumable ok.
        dao.deleteById("a")
        dao.insert(completed(id = "a", startedAt = 100))
        dao.insert(resumable(id = "b", startedAt = 200))
        assertEquals("b", dao.getResumable()?.id)
        assertEquals("a", dao.getById("a")?.id)
    }

    @Test
    fun temporaryRows_areExcludedFromHistoryPaging() = runBlocking {
        val dao = db.readingSessionDao()
        dao.insert(resumable(id = "temp", startedAt = 300, isPermanent = false))
        dao.insert(completed(id = "done", startedAt = 200))
        val page = loadHistory()
        assertEquals(listOf("done"), page.data.map(HistoryRowProjection::id))
    }

    @Test
    fun promotedResumable_appearsInPagingWithCheckpointEnd() = runBlocking {
        val dao = db.readingSessionDao()
        dao.insert(
            resumable(id = "live", startedAt = 300, isPermanent = true).copy(
                checkpointGlobalPageIndex = 7,
                checkpointPageIndex = 7,
                lastCheckpointAt = 350,
            ),
        )
        val page = loadHistory()
        val row = page.data.single()
        assertEquals("live", row.id)
        assertEquals(7, row.endGlobalPageIndex)
        assertEquals(350L, row.endedAt)
        assertEquals(ReadingSessionStatus.PAUSED.name, row.status)
    }

    @Test
    fun historyOrder_isStartedAtDescThenIdDesc() = runBlocking {
        val dao = db.readingSessionDao()
        dao.insert(completed(id = "a", startedAt = 100))
        dao.insert(completed(id = "c", startedAt = 100))
        dao.insert(completed(id = "b", startedAt = 200))
        val page = loadHistory()
        assertEquals(listOf("b", "c", "a"), page.data.map(HistoryRowProjection::id))
    }

    @Test
    fun fileKeyReassociates_currentShelfTitleAndAvailability() = runBlocking {
        val comicDao = db.comicDao()
        val sessionDao = db.readingSessionDao()
        comicDao.insert(
            ComicEntity(
                uri = "content://book/a",
                fileKey = "book-a",
                title = "Current Title",
                addedAt = 1L,
                coverPath = "/cover.png",
                isMissing = false,
                sourceType = BookSourceType.EXTERNAL_ARCHIVE,
            ),
        )
        sessionDao.insert(completed(id = "s1", startedAt = 10, fileKey = "book-a", title = "Old"))
        val page = loadHistory()
        val row = page.data.single()
        assertEquals("Current Title", row.currentTitle)
        assertEquals("/cover.png", row.coverPath)
        assertEquals(false, row.isMissing)

        comicDao.deleteById(row.comicId!!)
        val page2 = loadHistory()
        val unavailable = page2.data.single()
        assertNull(unavailable.comicId)
        assertEquals("Old", unavailable.titleSnapshot)
    }

    @Test
    fun deleteRestoreAndClear_areTransactional() = runBlocking {
        val dao = db.readingSessionDao()
        val entity = completed(id = "keep", startedAt = 50)
        dao.insert(entity)
        dao.insert(completed(id = "gone", startedAt = 40))
        dao.deleteById("keep")
        assertNull(dao.getById("keep"))
        dao.insert(entity)
        assertEquals("keep", dao.getById("keep")?.id)
        dao.clearHistory()
        assertEquals(0L, dao.countPermanent())
        assertNull(dao.getResumable())
    }

    @Test
    fun checkpointAndEndColumns_areIndependentOnCompletedRows() = runBlocking {
        val dao = db.readingSessionDao()
        val entity = completed(id = "x", startedAt = 1).copy(
            checkpointGlobalPageIndex = 5,
            checkpointPageIndex = 5,
            checkpointPageIdentity = "cp",
            endGlobalPageIndex = 9,
            endPageIndex = 9,
            endPageIdentity = "end",
        )
        dao.insert(entity)
        val stored = dao.getById("x")!!
        assertEquals(5, stored.checkpointGlobalPageIndex)
        assertEquals(9, stored.endGlobalPageIndex)
    }

    private suspend fun loadHistory(): PagingSource.LoadResult.Page<Int, HistoryRowProjection> {
        val result = db.readingSessionDao().observeHistoryPaging().load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        return result as PagingSource.LoadResult.Page<Int, HistoryRowProjection>
    }

    private fun resumable(
        id: String,
        startedAt: Long,
        isPermanent: Boolean = false,
        fileKey: String = "book-a",
    ) = base(
        id = id,
        startedAt = startedAt,
        status = ReadingSessionStatus.PAUSED.name,
        isPermanent = isPermanent,
        resumableSlot = ReadingSessionEntity.RESUMABLE_SLOT,
        endedAt = null,
        endReason = null,
        fileKey = fileKey,
        fillEnd = false,
    )

    private fun completed(
        id: String,
        startedAt: Long,
        fileKey: String = "book-a",
        title: String = "Book A",
    ) = base(
        id = id,
        startedAt = startedAt,
        status = ReadingSessionStatus.COMPLETED.name,
        isPermanent = true,
        resumableSlot = null,
        endedAt = startedAt + 1_000,
        endReason = ReadingSessionEndReason.LEFT_READER.name,
        fileKey = fileKey,
        title = title,
        fillEnd = true,
    )

    private fun base(
        id: String,
        startedAt: Long,
        status: String,
        isPermanent: Boolean,
        resumableSlot: Int?,
        endedAt: Long?,
        endReason: String?,
        fileKey: String,
        title: String = "Book A",
        fillEnd: Boolean,
    ) = ReadingSessionEntity(
        id = id,
        comicFileKey = fileKey,
        titleSnapshot = title,
        sourceType = BookSourceType.EXTERNAL_ARCHIVE,
        status = status,
        startedAt = startedAt,
        lastCheckpointAt = startedAt + 500,
        endedAt = endedAt,
        activeReadingMillis = 40_000,
        endReason = endReason,
        timeZoneId = "UTC",
        isPermanent = isPermanent,
        resumableSlot = resumableSlot,
        startPageIdentity = "p0",
        startGlobalPageIndex = 0,
        startChapterIndex = 0,
        startPageIndex = 0,
        startChapterTitle = "Ch 1",
        startSourceRevision = "rev",
        checkpointPageIdentity = "p1",
        checkpointGlobalPageIndex = 1,
        checkpointChapterIndex = 0,
        checkpointPageIndex = 1,
        checkpointChapterTitle = "Ch 1",
        checkpointSourceRevision = "rev",
        endPageIdentity = if (fillEnd) "p1" else null,
        endGlobalPageIndex = if (fillEnd) 1 else null,
        endChapterIndex = if (fillEnd) 0 else null,
        endPageIndex = if (fillEnd) 1 else null,
        endChapterTitle = if (fillEnd) "Ch 1" else null,
        endSourceRevision = if (fillEnd) "rev" else null,
    )
}
