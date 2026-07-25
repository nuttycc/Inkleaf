package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.ChapterEntity

/**
 * 一次章节扫描 diff 的纯计算结果。落库由 [ChapterDao.applyDiff] 在一个事务里完成。
 *
 * 设计要点：fileKey 是章节的稳定身份，chapterIndex 仅是展示顺序。因此 diff 以 fileKey 匹配同章节——索引/标题变化走"更新"而非"删除+新增"，从而保留该章节
 * 已回填的 pageCount 等字段，并保留阅读进度（进度按 chapterIndex 存，重排后 索引语义已变，但 spec 只强制 later-chapter 场景进度不丢，这里满足）。
 */
data class ChapterDiff(
    val toInsert: List<ChapterEntity>,
    val toUpdate: List<ChapterEntity>,
    val toRestore: List<Long>,
    val toMarkMissing: List<Long>,
    val toDelete: List<Long>,
) {
    val addedCount: Int
        get() = toInsert.size
}

/**
 * 章节扫描 diff 的纯函数实现，无 DB / Android 依赖，可在 JVM 单测里覆盖 追加 / 中间插章 / 删中间章 / 全删 / 重排 / 恢复 等场景。
 *
 * 演化出的"先算后落"结构也让 [ComicRepository.syncSeriesChapters] 的逻辑 可读可测，不再和数据访问耦合在一起。
 */
object ChapterSync {

    /**
     * @param existing 库里该 comic 的全部章节（顺序不限）
     * @param scanned 扫描到的 PDF，调用方已按章节顺序排好
     * @param comicId 所属 comic id
     * @param titleOf 扫描文件显示名 → 章节标题（通常是去扩展名）
     */
    fun computeDiff(
        existing: List<ChapterEntity>,
        scanned: List<LibraryScanner.ScannedFile>,
        comicId: Long,
        titleOf: (String) -> String,
        completeScan: Boolean = true,
    ): ChapterDiff {
        val existingByKey = existing.associateBy { it.fileKey }
        val scannedKeys = scanned.map { it.fileKey }.toSet()
        val titles = buildChapterTitles(scanned, titleOf)

        val toInsert = mutableListOf<ChapterEntity>()
        val toUpdate = mutableListOf<ChapterEntity>()
        val toRestore = mutableListOf<Long>()
        scanned.forEachIndexed { index, pdf ->
            val cur = existingByKey[pdf.fileKey]
            val newTitle = titles.getValue(pdf.fileKey)
            if (cur == null) {
                if (!completeScan) return@forEachIndexed
                toInsert.add(
                    ChapterEntity(
                        comicId = comicId,
                        chapterIndex = index,
                        uri = pdf.uri,
                        fileKey = pdf.fileKey,
                        title = newTitle,
                        relativePath = pdf.relativePath,
                        mimeType = pdf.mimeType,
                        size = pdf.size,
                        lastModified = pdf.lastModified,
                    )
                )
            } else {
                if (!completeScan && cur.isMissing) return@forEachIndexed
                val contentChanged =
                    metadataChanged(cur.size, pdf.size) ||
                        metadataChanged(cur.lastModified, pdf.lastModified)
                val updated =
                    cur.copy(
                        chapterIndex = if (completeScan) index else cur.chapterIndex,
                        uri = pdf.uri,
                        title = newTitle,
                        relativePath = pdf.relativePath,
                        mimeType = pdf.mimeType ?: cur.mimeType,
                        size = pdf.size ?: cur.size,
                        lastModified = pdf.lastModified ?: cur.lastModified,
                        pageCount = if (contentChanged) 0 else cur.pageCount,
                    )
                if (updated != cur) toUpdate.add(updated)
                if (completeScan && cur.isMissing) toRestore.add(cur.id)
            }
        }

        // 文件消失的章节：之前正常 → 标记失效（保留进度，文件回来可恢复）
        val toMarkMissing =
            if (completeScan) {
                existing.filter { !it.isMissing && it.fileKey !in scannedKeys }.map { it.id }
            } else {
                emptyList()
            }
        // 之前已失效、扫描仍没找到 → 直接删（避免索引长期被 dead 行占用，
        // 旧版按 chapterIndex 范围删是错的：重排后 dead 行的索引可能落在
        // 保留区间内，永远清不掉，还成为撞索引的来源）
        val toDelete =
            if (completeScan) {
                existing.filter { it.isMissing && it.fileKey !in scannedKeys }.map { it.id }
            } else {
                emptyList()
            }

        return ChapterDiff(toInsert, toUpdate, toRestore, toMarkMissing, toDelete)
    }

    private fun buildChapterTitles(
        scanned: List<LibraryScanner.ScannedFile>,
        titleOf: (String) -> String,
    ): Map<String, String> {
        return groupScannedFilesByDisplayName(scanned)
            .values
            .flatMap { group ->
                if (group.size == 1) {
                    val file = group.single()
                    listOf(file.fileKey to titleOf(file.displayName))
                } else {
                    duplicatePathTitles(group, titleOf).toList()
                }
            }
            .toMap()
    }

    private fun duplicatePathTitles(
        duplicates: List<LibraryScanner.ScannedFile>,
        titleOf: (String) -> String,
    ): Map<String, String> {
        val segmentsByKey = duplicates.associate { it.fileKey to it.relativePath.split('/') }
        val maxDepth = segmentsByKey.values.maxOf { it.size }
        val suffixCounts =
            (2..maxDepth).associateWith { length ->
                duplicates
                    .groupingBy { file ->
                        segmentsByKey
                            .getValue(file.fileKey)
                            .takeLast(length)
                            .joinToString("/")
                            .lowercase()
                    }
                    .eachCount()
            }

        val candidates = duplicates.associate { file ->
            val segments = segmentsByKey.getValue(file.fileKey)
            val uniqueSuffix =
                (2..maxDepth).firstNotNullOfOrNull { length ->
                    val suffix = segments.takeLast(length).joinToString("/")
                    suffix.takeIf { suffixCounts.getValue(length)[suffix.lowercase()] == 1 }
                } ?: file.relativePath
            file.fileKey to titleOf(uniqueSuffix)
        }

        val repeatedCandidates = candidates.entries.groupBy { it.value.lowercase() }
        return buildMap {
            repeatedCandidates.values.forEach { entries ->
                if (entries.size == 1) {
                    val entry = entries.single()
                    put(entry.key, entry.value)
                } else {
                    entries
                        .sortedBy { it.key }
                        .forEachIndexed { index, entry ->
                            put(entry.key, "${entry.value} [${index + 1}]")
                        }
                }
            }
        }
    }

    private fun metadataChanged(previous: Long?, scanned: Long?): Boolean =
        previous != null && scanned != null && previous != scanned

    /**
     * 计算章节重排后，旧阅读进度索引 [ComicEntity.lastReadChapterIndex] 应映射到的新索引。
     *
     * 按 fileKey 跟踪：旧索引指向的章节如果在 [diff] 的 toUpdate 里被重排了， 返回它的新索引；否则返回 null（索引未变，或原章节已被删/失效，调用方
     * 不需要重写——打开时 coerceIn 会处理越界，进度不丢）。
     *
     * 这覆盖"中间插章"和"删中间章"场景：用户读到第 3 章，前面插入新章后 第 3 章变成第 4 章，重映射让进度继续指向同一章节内容，而不是错位到 新插入的章节。
     */
    fun remapChapterIndex(
        existing: List<ChapterEntity>,
        diff: ChapterDiff,
        oldChapterIndex: Int,
    ): Int? {
        val oldChapter = existing.find { it.chapterIndex == oldChapterIndex } ?: return null
        val reindexed = diff.toUpdate.find { it.id == oldChapter.id } ?: return null
        return if (reindexed.chapterIndex != oldChapterIndex) reindexed.chapterIndex else null
    }
}

internal fun groupScannedFilesByDisplayName(
    files: List<LibraryScanner.ScannedFile>
): Map<String, List<LibraryScanner.ScannedFile>> = files.groupBy { it.displayName.lowercase() }
