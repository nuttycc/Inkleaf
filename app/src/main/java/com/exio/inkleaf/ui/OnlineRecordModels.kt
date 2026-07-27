package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.plugin.OnlineAvailability
import com.exio.inkleaf.plugin.OnlineComicRecord
import com.exio.inkleaf.plugin.OnlinePageBookmark
import com.exio.inkleaf.plugin.OnlinePageFavorite
import com.exio.inkleaf.plugin.OnlineReadingSessionRecord
import com.exio.inkleaf.plugin.PageImage
import java.io.File

data class OnlineReaderTarget(
    val pluginId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterRevision: String?,
    val opaqueContextJson: String?,
    val initialPageId: String?,
    val initialPageIndex: Int,
)

internal data class OnlineSavedBookmarkUi(
    val key: String,
    val title: String,
    val chapterTitle: String,
    val chapterIndex: Int?,
    val pageIndex: Int,
    val addedAtMs: Long,
    val cover: PageImage?,
    val availability: OnlineAvailability,
    val target: OnlineReaderTarget,
    val stored: OnlinePageBookmark,
)

internal data class OnlineSavedFavoriteUi(
    val key: String,
    val title: String,
    val chapterTitle: String,
    val pageIndex: Int,
    val addedAtMs: Long,
    val snapshotFile: File?,
    val availability: OnlineAvailability,
    val target: OnlineReaderTarget,
    val stored: OnlinePageFavorite,
)

internal data class OnlineHistorySessionUi(
    val key: String,
    val title: String,
    val endLocationLabel: String,
    val timeRangeLabel: String,
    val durationLabel: String,
    val cover: PageImage?,
    val availability: OnlineAvailability,
    val target: OnlineReaderTarget,
    val stored: OnlineReadingSessionRecord,
)

internal fun OnlineAvailability.canOpenReader(): Boolean =
    this == OnlineAvailability.AVAILABLE || this == OnlineAvailability.TEMPORARY_ERROR

internal fun OnlineAvailability.displayLabel(): String =
    when (this) {
        OnlineAvailability.AVAILABLE -> "可用"
        OnlineAvailability.PLUGIN_DISABLED -> "插件已禁用"
        OnlineAvailability.PLUGIN_UNINSTALLED -> "插件已卸载"
        OnlineAvailability.PLUGIN_INCOMPATIBLE -> "插件不兼容"
        OnlineAvailability.AUTH_REQUIRED -> "需要登录"
        OnlineAvailability.CONTENT_MISSING -> "内容已失效"
        OnlineAvailability.TEMPORARY_ERROR -> "上次加载失败，可重试"
    }

internal fun OnlineComicRecord.readerTarget(location: OnlinePageLocation): OnlineReaderTarget {
    val chapterId = location.identity.chapter.chapterId
    val chapter = chapters.firstOrNull { it.chapterId == chapterId }
    val opaqueContext = chapter?.opaqueContext ?: detail?.opaqueContext
    return OnlineReaderTarget(
        pluginId = key.pluginId,
        sourceId = key.sourceId,
        chapterId = chapterId,
        chapterRevision = location.chapterRevision ?: chapter?.revision,
        opaqueContextJson = opaqueContext?.toString(),
        initialPageId = location.identity.pageId,
        initialPageIndex = location.pageIndex,
    )
}

internal fun OnlineComicRecord.titleSnapshot(): String =
    detail?.title?.takeIf(String::isNotBlank) ?: key.sourceId

internal fun OnlineComicRecord.chapterTitle(location: OnlinePageLocation): String =
    chapters
        .firstOrNull { it.chapterId == location.identity.chapter.chapterId }
        ?.title
        ?.takeIf(String::isNotBlank) ?: location.identity.chapter.chapterId
