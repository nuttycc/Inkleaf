package com.exio.inkleaf.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.exio.inkleaf.data.ComicRepository
import com.exio.inkleaf.data.HistoryDateGrouping
import com.exio.inkleaf.data.ReadingPositionResolution
import com.exio.inkleaf.data.ReadingPositionResolver
import com.exio.inkleaf.data.ReadingSessionRepository
import com.exio.inkleaf.data.SystemReadingClock
import com.exio.inkleaf.data.db.BookSourceType
import com.exio.inkleaf.data.db.HistoryRowProjection
import com.exio.inkleaf.data.db.ReadingSessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface HistoryListItem {
    val stableKey: String

    data class DateHeader(
        val localDate: LocalDate,
        val label: String,
    ) : HistoryListItem {
        override val stableKey: String = "date:${HistoryDateGrouping.dateKey(localDate)}"
    }

    data class Session(val row: HistorySessionUi) : HistoryListItem {
        override val stableKey: String = "session:${row.id}"
    }
}

data class HistorySessionUi(
    val id: String,
    val title: String,
    val coverPath: String?,
    val endLocationLabel: String,
    val timeRangeLabel: String,
    val durationLabel: String,
    val comicId: Long?,
    val contentAvailable: Boolean,
    val startedAt: Long,
    val timeZoneId: String,
    val endGlobalPageIndex: Int,
    val endPageIdentity: String?,
    val endSourceRevision: String,
    val sourceType: BookSourceType,
)

sealed interface HistoryEvent {
    data class SessionDeleted(val snapshot: ReadingSessionEntity) : HistoryEvent
    data class Message(val text: String) : HistoryEvent
    data class NavigateToReader(val comicId: Long, val page: Int) : HistoryEvent
    data class ConfirmSourceChanged(
        val comicId: Long,
        val approximatePage: Int,
        val locationLabel: String?,
    ) : HistoryEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val sessionRepo = ReadingSessionRepository.getInstance(app)
    private val comicRepo = ComicRepository(app)
    private val clock = SystemReadingClock()
    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    private val todayRefresh = MutableStateFlow(0)

    /** Session id currently resolving for continue-reading; null when idle. */
    var resolvingSessionId by mutableStateOf<String?>(null)
        private set

    private var resolveGeneration = 0L
    private var resolveJob: Job? = null

    val events = eventChannel.receiveAsFlow()

    val timeline: Flow<PagingData<HistoryListItem>> = todayRefresh
        .flatMapLatest {
            val today = Instant.ofEpochMilli(clock.nowMillis())
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            sessionRepo.historyPaging()
                .map { paging ->
                    paging
                        .map { row -> row.toHistoryListItem() }
                        .insertSeparators { before, after ->
                            val afterSession = after as? HistoryListItem.Session
                                ?: return@insertSeparators null
                            val afterDate = HistoryDateGrouping.sessionLocalDate(
                                afterSession.row.startedAt,
                                afterSession.row.timeZoneId,
                            )
                            val beforeDate = (before as? HistoryListItem.Session)?.let {
                                HistoryDateGrouping.sessionLocalDate(
                                    it.row.startedAt,
                                    it.row.timeZoneId,
                                )
                            }
                            if (beforeDate == afterDate) {
                                null
                            } else {
                                HistoryListItem.DateHeader(
                                    localDate = afterDate,
                                    label = HistoryDateGrouping.labelFor(
                                        afterDate,
                                        today,
                                        Locale.getDefault(),
                                    ),
                                )
                            }
                        }
                }
        }
        .cachedIn(viewModelScope)

    fun refreshDateLabels() {
        todayRefresh.value += 1
    }

    fun continueReading(session: HistorySessionUi) {
        if (!session.contentAvailable || session.comicId == null) return
        if (resolvingSessionId == session.id) return
        resolveJob?.cancel()
        val generation = ++resolveGeneration
        resolvingSessionId = session.id
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val comic = comicRepo.getComic(session.comicId)
                    ?.takeUnless { it.isMissing || it.isDraft }
                if (comic == null) {
                    if (generation == resolveGeneration) {
                        eventChannel.send(HistoryEvent.Message("漫画内容当前不可用"))
                    }
                    return@launch
                }
                val volume = try {
                    comicRepo.openBook(comic)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == resolveGeneration) {
                        eventChannel.send(HistoryEvent.Message("漫画内容当前不可用"))
                    }
                    return@launch
                }
                try {
                    val resolution = ReadingPositionResolver.resolve(
                        sourceType = session.sourceType,
                        storedSourceRevision = session.endSourceRevision,
                        storedGlobalPage = session.endGlobalPageIndex,
                        pageIdentity = session.endPageIdentity,
                        currentSourceRevision = volume.sourceRevision,
                        currentPageCount = volume.totalPageCount,
                        findPageByIdentity = volume::findPageByIdentity,
                    )
                    if (generation != resolveGeneration) return@launch
                    when (resolution) {
                        is ReadingPositionResolution.Ready -> {
                            eventChannel.send(
                                HistoryEvent.NavigateToReader(comic.id, resolution.globalPage),
                            )
                        }
                        is ReadingPositionResolution.SourceChanged -> {
                            val loc = volume.globalToChapterPage(resolution.approximateGlobalPage)
                            val label = formatEndLocation(
                                chapterTitle = volume.chapterTitle(loc.chapterIndex),
                                chapterIndex = loc.chapterIndex,
                                pageIndex = loc.pageIndex,
                            )
                            eventChannel.send(
                                HistoryEvent.ConfirmSourceChanged(
                                    comicId = comic.id,
                                    approximatePage = resolution.approximateGlobalPage,
                                    locationLabel = label,
                                ),
                            )
                        }
                        is ReadingPositionResolution.Unavailable -> {
                            eventChannel.send(HistoryEvent.Message(resolution.message))
                        }
                    }
                } finally {
                    volume.close()
                }
            } finally {
                if (generation == resolveGeneration) {
                    resolvingSessionId = null
                    resolveJob = null
                }
            }
        }
        resolveJob = job
        job.start()
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val snapshot = sessionRepo.deletePermanent(sessionId)
                if (snapshot != null) {
                    eventChannel.send(HistoryEvent.SessionDeleted(snapshot))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                eventChannel.send(HistoryEvent.Message("删除阅读记录失败"))
            }
        }
    }

    fun restoreSession(snapshot: ReadingSessionEntity) {
        viewModelScope.launch {
            try {
                sessionRepo.restorePermanent(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                eventChannel.send(HistoryEvent.Message("恢复阅读记录失败"))
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                sessionRepo.clearHistory()
                eventChannel.send(HistoryEvent.Message("已清空阅读历史"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                eventChannel.send(HistoryEvent.Message("清空阅读历史失败"))
            }
        }
    }

    fun cancelPendingResolve() {
        resolveGeneration += 1
        resolveJob?.cancel()
        resolveJob = null
        resolvingSessionId = null
    }
}

private fun HistoryRowProjection.toHistoryListItem(): HistoryListItem =
    HistoryListItem.Session(toUi())

private fun HistoryRowProjection.toUi(): HistorySessionUi {
    val available = comicId != null && isMissing != true && isDraft != true
    val title = when {
        available && !currentTitle.isNullOrBlank() -> currentTitle
        else -> titleSnapshot
    }
    return HistorySessionUi(
        id = id,
        title = title,
        coverPath = coverPath.takeIf { available },
        endLocationLabel = formatEndLocation(endChapterTitle, endChapterIndex, endPageIndex),
        timeRangeLabel = formatTimeRange(startedAt, endedAt, timeZoneId),
        durationLabel = formatDuration(activeReadingMillis),
        comicId = comicId.takeIf { available },
        contentAvailable = available,
        startedAt = startedAt,
        timeZoneId = timeZoneId,
        endGlobalPageIndex = endGlobalPageIndex,
        endPageIdentity = endPageIdentity,
        endSourceRevision = endSourceRevision,
        sourceType = sourceType,
    )
}

private fun formatEndLocation(
    chapterTitle: String,
    chapterIndex: Int,
    pageIndex: Int,
): String {
    val chapter = chapterTitle.ifBlank { "第 ${chapterIndex + 1} 章" }
    return "$chapter · 第 ${pageIndex + 1} 页"
}

private fun formatTimeRange(startedAt: Long, endedAt: Long, zoneId: String): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(endedAt).atZone(zone).toLocalTime()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    return "${start.format(fmt)}-${end.format(fmt)}"
}

private fun formatDuration(activeMillis: Long): String {
    val totalMinutes = (activeMillis / 60_000L).coerceAtLeast(0L)
    return if (totalMinutes < 1L) {
        "阅读不足 1 分钟"
    } else {
        "阅读 ${totalMinutes} 分钟"
    }
}
