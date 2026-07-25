package com.exio.inkleaf.data

import com.exio.inkleaf.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节扫描 diff 的 JVM 单测。覆盖 spec 建议的 "folder scan diff behavior"： 追加 / 中间插章 / 删中间章 / 全删 / 恢复 / 重排 /
 * no-op。
 *
 * 这里专门覆盖缺陷 1 的两个撞索引场景：中间插章 + 删中间章后重扫。 不依赖 Room/Android，纯函数验证 diff 计算正确。
 */
class ChapterSyncTest {

    private val comicId = 100L
    private val titleOf: (String) -> String = { name -> name.substringBeforeLast('.') }

    private fun scanned(displayName: String, key: String = displayName) =
        LibraryScanner.ScannedFile(uri = "uri://$key", fileKey = key, displayName = displayName)

    private fun existing(
        id: Long,
        index: Int,
        fileKey: String,
        // 默认与 titleOf 的产物一致（去扩展名），避免无意义的 title-diff
        // 把 no-op 场景误判成 toUpdate。需要测 title 变更时显式传旧标题。
        title: String = fileKey.substringBeforeLast('.'),
        pageCount: Int = 10,
        isMissing: Boolean = false,
    ) =
        ChapterEntity(
            id = id,
            comicId = comicId,
            chapterIndex = index,
            uri = "uri://$fileKey",
            fileKey = fileKey,
            title = title,
            relativePath = fileKey,
            pageCount = pageCount,
            isMissing = isMissing,
        )

    @Test
    fun `first import inserts all chapters in scanned order`() {
        val scanned = listOf(scanned("1.pdf"), scanned("2.pdf"), scanned("3.pdf"))

        val diff = ChapterSync.computeDiff(emptyList(), scanned, comicId, titleOf)

        assertTrue(diff.toUpdate.isEmpty())
        assertTrue(diff.toMarkMissing.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
        assertEquals(3, diff.toInsert.size)
        assertEquals(listOf(0, 1, 2), diff.toInsert.map { it.chapterIndex })
        assertEquals(listOf("1", "2", "3"), diff.toInsert.map { it.title })
    }

    @Test
    fun `rescan with no changes is a no-op`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
            )
        val scanned = listOf(scanned("1.pdf"), scanned("2.pdf"))

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toUpdate.isEmpty())
        assertTrue(diff.toMarkMissing.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
        assertTrue(diff.toRestore.isEmpty())
    }

    /**
     * 缺陷 1 的核心场景：[1,3,4].pdf 中间插入 2.pdf。 旧实现逐行 update 把 3.pdf→idx1、4.pdf→idx2，撞唯一索引崩。 diff 应以
     * fileKey 匹配，3/4 走 toUpdate 改索引，2 走 toInsert。
     */
    @Test
    fun `insert chapter in middle shifts later indices`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "3.pdf"),
                existing(id = 3, index = 2, fileKey = "4.pdf"),
            )
        val scanned =
            listOf(
                scanned("1.pdf"),
                scanned("2.pdf"),
                scanned("3.pdf"),
                scanned("4.pdf"),
            )

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 1.pdf 索引未变，不出现在 toUpdate
        // 2.pdf 是新的
        assertEquals(1, diff.toInsert.size)
        assertEquals("2.pdf", diff.toInsert[0].fileKey)
        assertEquals(1, diff.toInsert[0].chapterIndex)
        // 3.pdf: idx1→2, 4.pdf: idx2→3
        assertEquals(2, diff.toUpdate.size)
        val updateByKey = diff.toUpdate.associateBy { it.fileKey }
        assertEquals(2, updateByKey["3.pdf"]?.chapterIndex)
        assertEquals(3, updateByKey["4.pdf"]?.chapterIndex)
        // 没有删除/失效
        assertTrue(diff.toMarkMissing.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
        // 保留 pageCount（已回填字段不丢）
        assertEquals(10, updateByKey["3.pdf"]?.pageCount)
    }

    /**
     * 缺陷 1 的另一个场景：[1,2,3] 删掉中间 2.pdf 后重扫。 旧 deleteExceptIndices 按 [0,1] 删除索引 2，但 3.pdf 被 update 到
     * idx1 后 dead 行 2.pdf 也在 idx1 → 永远清不掉。diff 应按 id 删 2.pdf、按 id 更新 3.pdf。
     */
    @Test
    fun `delete middle chapter reindexes later ones by id`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
                existing(id = 3, index = 2, fileKey = "3.pdf"),
            )
        val scanned = listOf(scanned("1.pdf"), scanned("3.pdf"))

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 1.pdf 不变；3.pdf idx2→1
        assertEquals(1, diff.toUpdate.size)
        assertEquals("3.pdf", diff.toUpdate[0].fileKey)
        assertEquals(1, diff.toUpdate[0].chapterIndex)
        // 2.pdf 之前正常、现在消失 → 标记失效（保留进度，可恢复）
        assertEquals(listOf(2L), diff.toMarkMissing)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `previously missing chapter that comes back is restored`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf", isMissing = true),
            )
        val scanned = listOf(scanned("1.pdf"), scanned("2.pdf"))

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        assertTrue(diff.toUpdate.isEmpty())
        assertEquals(listOf(2L), diff.toRestore)
        assertTrue(diff.toMarkMissing.isEmpty())
    }

    /**
     * 缺陷 4 的核心场景：之前已失效、重扫仍没找到的章节必须删掉。 旧 deleteExceptIndices 按索引范围删，重排后 dead 行索引可能落在保留区间，
     * 永远清不掉——这正是缺陷 1 撞索引的来源之一。diff 按 id 删，干净。
     */
    @Test
    fun `long-missing chapter not in scan is deleted by id`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                // 2.pdf 之前就失效了，重扫仍然没有
                existing(id = 2, index = 1, fileKey = "2.pdf", isMissing = true),
                existing(id = 3, index = 2, fileKey = "3.pdf"),
            )
        val scanned = listOf(scanned("1.pdf"), scanned("3.pdf"))

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 2.pdf 直接删除（不再保留 dead 行）
        assertEquals(listOf(2L), diff.toDelete)
        // 3.pdf idx2→1
        assertEquals(1, diff.toUpdate.size)
        assertEquals(1, diff.toUpdate[0].chapterIndex)
        // 1.pdf 不变
        assertTrue(diff.toMarkMissing.isEmpty())
    }

    @Test
    fun `all chapters removed marks existing as missing`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
            )

        val diff = ChapterSync.computeDiff(existing, emptyList(), comicId, titleOf)

        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toUpdate.isEmpty())
        // 之前正常的 → 标记失效（不直接删，保留进度等文件回来）
        assertEquals(listOf(1L, 2L), diff.toMarkMissing.sorted())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `title rename without index change triggers update`() {
        val existing = listOf(existing(id = 1, index = 0, fileKey = "1.pdf", title = "old title"))
        val scanned = listOf(scanned("1.pdf"))

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        assertEquals(1, diff.toUpdate.size)
        assertEquals("1", diff.toUpdate[0].title)
        assertEquals(0, diff.toUpdate[0].chapterIndex)
    }

    @Test
    fun `append new chapter at end leaves existing untouched`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
            )
        val scanned =
            listOf(
                scanned("1.pdf"),
                scanned("2.pdf"),
                scanned("3.pdf"),
            )

        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        assertEquals(1, diff.toInsert.size)
        assertEquals("3.pdf", diff.toInsert[0].fileKey)
        assertEquals(2, diff.toInsert[0].chapterIndex)
        assertTrue(diff.toUpdate.isEmpty())
    }

    @Test
    fun `duplicate display names use shortest unique relative path titles`() {
        val scanned =
            listOf(
                scanned("1.pdf", "a").copy(relativePath = "part-a/1.pdf"),
                scanned("1.pdf", "b").copy(relativePath = "part-b/1.pdf"),
            )

        val diff = ChapterSync.computeDiff(emptyList(), scanned, comicId, titleOf)

        assertEquals(listOf("part-a/1", "part-b/1"), diff.toInsert.map { it.title })
    }

    @Test
    fun `partial scan defers new chapters and removals until a complete scan`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
            )
        val scanned = listOf(scanned("3.pdf"))

        val diff =
            ChapterSync.computeDiff(
                existing = existing,
                scanned = scanned,
                comicId = comicId,
                titleOf = titleOf,
                completeScan = false,
            )

        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toUpdate.isEmpty())
        assertTrue(diff.toMarkMissing.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `changed file metadata invalidates cached page count`() {
        val existing =
            existing(id = 1, index = 0, fileKey = "1.pdf")
                .copy(
                    size = 100,
                    lastModified = 10,
                )
        val scanned = scanned("1.pdf").copy(size = 120, lastModified = 11)

        val diff = ChapterSync.computeDiff(listOf(existing), listOf(scanned), comicId, titleOf)

        assertEquals(0, diff.toUpdate.single().pageCount)
    }

    // ===== 进度重映射 =====

    /** 用户读到第 3 章（index=2），前面插入新章后第 3 章变成 index=3。 重映射应让进度继续指向同一章节（3.pdf）的新索引 3，而不是错位到新插入的章节。 */
    @Test
    fun `remap chapter index after inserting in middle`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
                existing(id = 3, index = 2, fileKey = "3.pdf"), // 用户读到这章
            )
        val scanned =
            listOf(
                scanned("1.pdf"),
                scanned("1.5.pdf"),
                scanned("2.pdf"),
                scanned("3.pdf"),
            )
        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 3.pdf 从 index=2 重排到 index=3
        val newIndex = ChapterSync.remapChapterIndex(existing, diff, oldChapterIndex = 2)
        assertEquals(3, newIndex)
    }

    /** 删中间章后，用户读到的章节前移。重映射应指向新索引。 */
    @Test
    fun `remap chapter index after deleting in middle`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
                existing(id = 3, index = 2, fileKey = "3.pdf"), // 用户读到这章
            )
        // 2.pdf 被删
        val scanned = listOf(scanned("1.pdf"), scanned("3.pdf"))
        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 3.pdf 从 index=2 重排到 index=1
        val newIndex = ChapterSync.remapChapterIndex(existing, diff, oldChapterIndex = 2)
        assertEquals(1, newIndex)
    }

    /** 末尾追加章节不影响已读章节的索引，重映射返回 null（无需更新）。 */
    @Test
    fun `remap returns null when index unchanged`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf"),
            )
        val scanned =
            listOf(
                scanned("1.pdf"),
                scanned("2.pdf"),
                scanned("3.pdf"),
            )
        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 用户读到 index=1（2.pdf），它没被重排
        val newIndex = ChapterSync.remapChapterIndex(existing, diff, oldChapterIndex = 1)
        assertEquals(null, newIndex)
    }

    /** 用户读到的章节被删除（失效后清理）：不重写索引，让打开时 coerceIn 兜底。 */
    @Test
    fun `remap returns null when read chapter is gone`() {
        val existing =
            listOf(
                existing(id = 1, index = 0, fileKey = "1.pdf"),
                existing(id = 2, index = 1, fileKey = "2.pdf", isMissing = true), // 用户读到这章，但它已失效
            )
        // 2.pdf 重扫仍然没有 → 被删除
        val scanned = listOf(scanned("1.pdf"))
        val diff = ChapterSync.computeDiff(existing, scanned, comicId, titleOf)

        // 2.pdf 不在 toUpdate（它在 toDelete），重映射返回 null
        val newIndex = ChapterSync.remapChapterIndex(existing, diff, oldChapterIndex = 1)
        assertEquals(null, newIndex)
    }
}
