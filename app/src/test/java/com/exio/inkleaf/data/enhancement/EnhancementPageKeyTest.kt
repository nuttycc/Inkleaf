package com.exio.inkleaf.data.enhancement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnhancementPageKeyTest {
    @Test
    fun cacheValueEmbedsPipelineRevision() {
        val value = enhancementPageCacheValue(
            sourceKey = "comic-1#0",
            modelId = "model-a",
            modelRevision = "m1",
            pipelineRevision = ENHANCEMENT_PIPELINE_REVISION,
        )
        assertEquals(
            "comic-1#0@model-a@m1@$ENHANCEMENT_PIPELINE_REVISION",
            value,
        )
    }

    @Test
    fun changingOnlyPipelineRevisionChangesCacheIdentity() {
        val sourceKey = "comic-1#0"
        val current = enhancementPageCacheValue(
            sourceKey = sourceKey,
            modelId = "model-a",
            modelRevision = "m1",
            pipelineRevision = ENHANCEMENT_PIPELINE_REVISION,
        )
        val previous = enhancementPageCacheValue(
            sourceKey = sourceKey,
            modelId = "model-a",
            modelRevision = "m1",
            pipelineRevision = ENHANCEMENT_PIPELINE_REVISION_LEGACY,
        )
        assertNotEquals(current, previous)
    }
}
