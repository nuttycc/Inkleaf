package com.exio.inkleaf.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceDetailActionResultTest {
    @Test
    fun `object message falls back without interrupting action completion`() {
        val result = buildJsonObject { put("message", buildJsonObject { put("text", "done") }) }

        assertEquals("刷新：操作完成", sourceActionMessage(result, "刷新"))
    }

    @Test
    fun `string message is shown`() {
        val result = buildJsonObject { put("message", "done") }

        assertEquals("done", sourceActionMessage(result, "刷新"))
    }
}
