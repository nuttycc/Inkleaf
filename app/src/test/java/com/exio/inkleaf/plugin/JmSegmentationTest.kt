package com.exio.inkleaf.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors mirror the reference algorithm in hect0x7/JMComic-Crawler-Python (MIT), jm_toolkit.
 * get_num / decode_and_save. */
class JmSegmentationTest {
    @Test
    fun `strip count follows the chapter id eras`() {
        val cases =
            listOf(
                // Below the source-provided scramble id nothing is stacked.
                ScrambleCase(SCRAMBLE_ID, 220_979L, "00001", 0),
                // The fixed ten-strip era, up to and including 268849.
                ScrambleCase(SCRAMBLE_ID, 220_980L, "00001", 10),
                ScrambleCase(SCRAMBLE_ID, 268_849L, "00001", 10),
                // Hash-derived strips, ten-way spread: ord of the last hex character, not its value.
                ScrambleCase(SCRAMBLE_ID, 268_850L, "00001", 6), // md5 tail 'f' -> 102 % 10 * 2 + 2
                ScrambleCase(SCRAMBLE_ID, 300_000L, "00001", 16), // 'a' -> 97
                ScrambleCase(SCRAMBLE_ID, 421_925L, "00001", 18), // 'b' -> 98
                // 421926 itself already uses the eight-way spread.
                ScrambleCase(SCRAMBLE_ID, 421_926L, "00001", 14), // 'f' -> 102 % 8
                ScrambleCase(SCRAMBLE_ID, 500_000L, "00001", 12), // '5' -> 53
                ScrambleCase(SCRAMBLE_ID, 421_927L, "00002", 14),
                ScrambleCase(SCRAMBLE_ID, 900_001L, "00015", 4),
                ScrambleCase(SCRAMBLE_ID, 1_234_567L, "page_1", 4),
            )

        cases.forEach {
            assertEquals(
                "strip count for chapter ${it.chapterId} image ${it.fileName}",
                it.expected,
                JmSegmentation.stripCount(SCRAMBLE_ID, it.chapterId, it.fileName),
            )
        }
    }

    @Test
    fun `moves reverse the strip order and cover every row exactly once`() {
        listOf(103 to 10, 100 to 16, 2_200 to 8, 7 to 2, 6 to 6).forEach { (height, strips) ->
            val moves = JmSegmentation.restoreMoves(height, strips)

            assertEquals(strips, moves.size)
            assertEquals(height, moves.sumOf { it.height })

            val destinations = moves.sortedBy { it.targetY }
            var cursor = 0
            destinations.forEach {
                assertEquals("destination strips must tile from $cursor", cursor, it.targetY)
                cursor += it.height
            }
            assertEquals(height, cursor)

            val sources = BooleanArray(height)
            moves.forEach { move ->
                assertTrue("source strip starts inside the image", move.sourceY in 0 until height)
                for (row in move.sourceY until move.sourceY + move.height) {
                    assertTrue("row $row read twice", !sources[row])
                    sources[row] = true
                }
            }
            assertTrue("every source row used", sources.all { it })

            // The bottom source strip is the first destination strip.
            assertEquals(0, destinations.first().targetY)
            assertEquals(height - destinations.first().height, destinations.first().sourceY)
        }
    }

    @Test
    fun `no work is scheduled for images that are already in order`() {
        assertTrue(JmSegmentation.restoreMoves(100, 1).isEmpty())
        assertTrue(JmSegmentation.restoreMoves(100, 0).isEmpty())
        assertTrue(JmSegmentation.restoreMoves(0, 10).isEmpty())
    }

    @Test
    fun `file names come from the last path segment without its suffix`() {
        assertEquals(
            "00001",
            JmSegmentation.fileNameFromUrl("https://cdn.example/media/photos/9001/00001.webp"),
        )
        assertEquals(
            "00001",
            JmSegmentation.fileNameFromUrl("https://cdn.example/media/photos/9001/00001.jpg?v=17#x"),
        )
        assertEquals("cover", JmSegmentation.fileNameFromUrl("https://cdn.example/cover"))
        assertEquals("", JmSegmentation.fileNameFromUrl("https://cdn.example/"))
    }

    @Test
    fun `page identities distinguish a declared transform`() {
        val plain = PageDescriptor(index = 0, url = "https://cdn.example/1.jpg")
        val transformed =
            plain.copy(
                imageTransform =
                    PageImageTransform(
                        type = PageImageTransformType.JM_SEGMENTATION,
                        scrambleId = 220_980,
                    )
            )
        val otherScramble = transformed.copy(imageTransform = transformed.imageTransform?.copy(scrambleId = 300_000))

        val identities =
            listOf(plain, transformed, otherScramble).map {
                onlinePageIdentities("r1", listOf(it)).single()
            }

        assertEquals(3, identities.distinct().size)
        assertNotEquals(identities[0], identities[1])
        assertTrue(identities[1].endsWith(":transform=JM_SEGMENTATION,220980"))
    }

    private data class ScrambleCase(
        val scrambleId: Long,
        val chapterId: Long,
        val fileName: String,
        val expected: Int,
    )

    private companion object {
        const val SCRAMBLE_ID = 220_980L
    }
}
