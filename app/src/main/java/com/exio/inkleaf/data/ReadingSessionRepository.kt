package com.exio.inkleaf.data

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.exio.inkleaf.data.db.AppDatabase
import com.exio.inkleaf.data.db.HistoryRowProjection
import com.exio.inkleaf.data.db.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Sole write boundary for reading-session state transitions.
 *
 * Applies [ReadingSessionEffect] values from [ReadingSessionStateMachine] inside
 * Room transactions so the unique resumable slot and permanence flags stay
 * consistent. UI and Reader never touch the DAO directly.
 *
 * One process-wide instance owns the in-memory machine. A [Mutex] serializes
 * hydrate / dispatch / clear so concurrent coroutines cannot race the machine.
 *
 * Spec: #15 repository transaction boundary, #18 Paging config.
 */
class ReadingSessionRepository private constructor(
    context: Context,
    private val clock: ReadingClock,
    private val idGenerator: () -> String,
) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val dao = db.readingSessionDao()
    private val mutex = Mutex()

    /** In-memory reducer; hydrated from DB on first use / process restore. */
    private val machine = ReadingSessionStateMachine(clock, idGenerator)
    private var hydrated = false

    fun historyPaging(): Flow<PagingData<HistoryRowProjection>> = Pager(
        config = PagingConfig(
            pageSize = 50,
            initialLoadSize = 100,
            prefetchDistance = 15,
            enablePlaceholders = false,
            maxSize = 250,
        ),
        pagingSourceFactory = { dao.observeHistoryPaging() },
    ).flow

    suspend fun deletePermanent(id: String): ReadingSessionEntity? = mutex.withLock {
        db.withTransaction {
            val existing = dao.getById(id) ?: return@withTransaction null
            if (!existing.isPermanent) return@withTransaction null
            dao.deleteById(id)
            existing
        }
    }

    /** Undo delete: re-insert the full snapshot with its original UUID. */
    suspend fun restorePermanent(entity: ReadingSessionEntity) = mutex.withLock {
        require(entity.isPermanent) { "Only permanent history rows can be restored" }
        require(entity.resumableSlot == null)
        require(entity.status == ReadingSessionStatus.COMPLETED.name)
        require(entity.endedAt != null && entity.endReason != null)
        db.withTransaction {
            dao.insert(entity)
        }
    }

    suspend fun clearHistory() = mutex.withLock {
        db.withTransaction {
            dao.clearHistory()
            machine.reset()
            machine.onEvent(ReadingSessionEvent.ProcessRestored(null))
            hydrated = true
        }
    }

    /** Dispatch a domain event and persist resulting effects atomically. */
    suspend fun dispatch(event: ReadingSessionEvent): List<ReadingSessionEffect> =
        mutex.withLock {
            hydrateLocked()
            db.withTransaction {
                val effects = machine.onEvent(event)
                applyEffects(effects)
                effects
            }
        }

    /** Caller must hold [mutex]. */
    private suspend fun hydrateLocked() {
        if (hydrated) return
        db.withTransaction {
            val existing = dao.getResumable()?.let(ReadingSessionMapping::toResumable)
            applyEffects(machine.onEvent(ReadingSessionEvent.ProcessRestored(existing)))
            hydrated = true
        }
    }

    private suspend fun applyEffects(effects: List<ReadingSessionEffect>) {
        for (effect in effects) {
            when (effect) {
                is ReadingSessionEffect.UpsertResumable -> {
                    val entity = ReadingSessionMapping.fromResumable(effect.session)
                    require(entity.resumableSlot == ReadingSessionEntity.RESUMABLE_SLOT)
                    require(entity.endedAt == null && entity.endReason == null)
                    require(entity.endGlobalPageIndex == null)
                    require(
                        entity.status == ReadingSessionStatus.ACTIVE.name ||
                            entity.status == ReadingSessionStatus.PAUSED.name,
                    )
                    val existing = dao.getById(entity.id)
                    if (existing == null) {
                        // Slot must be empty; unique index fails otherwise.
                        dao.insert(entity)
                    } else {
                        require(existing.resumableSlot == ReadingSessionEntity.RESUMABLE_SLOT) {
                            "Cannot update a non-resumable row as resumable"
                        }
                        dao.update(entity)
                    }
                }
                is ReadingSessionEffect.CompletePermanent -> {
                    val entity = ReadingSessionMapping.fromCompleted(effect.session)
                    require(entity.isPermanent)
                    require(entity.resumableSlot == null)
                    require(entity.status == ReadingSessionStatus.COMPLETED.name)
                    require(entity.endedAt != null && entity.endReason != null)
                    require(entity.endGlobalPageIndex != null)
                    // Release the resumable slot row, then insert COMPLETED.
                    dao.deleteById(effect.session.id)
                    dao.insert(entity)
                }
                is ReadingSessionEffect.DiscardTemporary -> {
                    dao.deleteById(effect.sessionId)
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ReadingSessionRepository? = null

        fun getInstance(
            context: Context,
            clock: ReadingClock = SystemReadingClock(),
            idGenerator: () -> String = { UUID.randomUUID().toString() },
        ): ReadingSessionRepository =
            instance ?: synchronized(this) {
                instance ?: ReadingSessionRepository(
                    context = context.applicationContext,
                    clock = clock,
                    idGenerator = idGenerator,
                ).also { instance = it }
            }
    }
}
