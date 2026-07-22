package com.exio.inkleaf.data.db

import com.exio.inkleaf.data.enhancement.ENHANCEMENT_PIPELINE_REVISION
import com.exio.inkleaf.data.enhancement.ENHANCEMENT_PIPELINE_REVISION_LEGACY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contract tests for bulk-task pipeline matching and completed-page result kinds.
 * Room migration SQL is covered by [AppDatabase.MIGRATION_14_15] defaults; instrumented
 * MigrationTestHelper can be added when room-testing is on the classpath.
 */
class EnhancementCachePipelineContractTest {
    @Test
    fun legacyPipelineRevisionDoesNotMatchCurrentPipeline() {
        assertEquals("1", ENHANCEMENT_PIPELINE_REVISION_LEGACY)
        assertEquals("3", ENHANCEMENT_PIPELINE_REVISION)
        assertFalse(ENHANCEMENT_PIPELINE_REVISION_LEGACY == ENHANCEMENT_PIPELINE_REVISION)
    }

    @Test
    fun taskMatchesOnlyWhenPipelineRevisionEqualsCurrent() {
        val legacy = sampleTask(pipelineRevision = ENHANCEMENT_PIPELINE_REVISION_LEGACY)
        val current = sampleTask(pipelineRevision = ENHANCEMENT_PIPELINE_REVISION)
        assertFalse(matchesCurrentPipeline(legacy))
        assertTrue(matchesCurrentPipeline(current))
    }

    @Test
    fun completedPageResultKindsAreDistinct() {
        assertEquals("enhanced", EnhancementCachePageResultKind.ENHANCED)
        assertEquals("skipped", EnhancementCachePageResultKind.SKIPPED)
        assertFalse(
            EnhancementCachePageResultKind.ENHANCED == EnhancementCachePageResultKind.SKIPPED,
        )
    }

    @Test
    fun completedEntityDefaultsToEnhancedKind() {
        val row = EnhancementCacheCompletedPageEntity(
            taskId = "t",
            page = 0,
            completedAt = 1L,
        )
        assertEquals(EnhancementCachePageResultKind.ENHANCED, row.resultKind)
    }

    @Test
    fun skippedKindCanBeStoredOnCompletedEntity() {
        val row = EnhancementCacheCompletedPageEntity(
            taskId = "t",
            page = 3,
            completedAt = 1L,
            resultKind = EnhancementCachePageResultKind.SKIPPED,
        )
        assertEquals(EnhancementCachePageResultKind.SKIPPED, row.resultKind)
    }

    private fun matchesCurrentPipeline(task: EnhancementCacheTaskEntity): Boolean =
        task.pipelineRevision == ENHANCEMENT_PIPELINE_REVISION

    private fun sampleTask(pipelineRevision: String) = EnhancementCacheTaskEntity(
        id = "id",
        comicId = 1L,
        modelId = "m",
        modelRevision = "mr",
        sourceRevision = "sr",
        pipelineRevision = pipelineRevision,
        startPageInclusive = 0,
        endPageInclusive = 10,
        nextPage = 0,
        completedPages = 0,
        totalPages = 11,
        status = EnhancementCacheTaskStatus.RUNNING,
        createdAt = 0L,
        updatedAt = 0L,
        activeSlot = 1,
    )
}
