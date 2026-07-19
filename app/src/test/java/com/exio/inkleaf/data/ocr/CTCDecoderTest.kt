package com.exio.inkleaf.data.ocr

import com.paddle.ocr.postprocess.CTCDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CTCDecoderTest {
    @Test
    fun repeatedLabelsCollapseUnlessSeparatedByBlank() {
        val decoded = decode(
            winners = listOf(
                0 to 0.9f,
                1 to 0.6f,
                1 to 0.9f,
                0 to 0.9f,
                1 to 0.8f,
                2 to 0.7f,
                0 to 0.9f,
            )
        )

        assertEquals("AAB", decoded.text)
        assertEquals(listOf("A", "A", "B"), decoded.characters.map { it.text })
        assertEquals(0.6f, decoded.characters[0].confidence, 0.001f)
        assertTrue(decoded.characters.zipWithNext().all { (first, second) ->
            first.endFraction <= second.startFraction
        })
    }

    @Test
    fun validTimeStepsIgnoreBatchPadding() {
        val winners = listOf(
            1 to 0.9f,
            0 to 0.9f,
            0 to 0.9f,
            2 to 0.99f,
            2 to 0.99f,
            2 to 0.99f,
        )
        val output = logits(winners)

        val decoded = CTCDecoder.decode(
            output = output,
            shape = longArrayOf(1, winners.size.toLong(), 3),
            characterList = listOf("A", "B"),
            validTimeSteps = intArrayOf(3),
        ).single()

        assertEquals("A", decoded.text)
    }

    @Test
    fun characterThresholdIsInclusiveAndBinary() {
        val decoded = decode(
            winners = listOf(
                1 to 0.54f,
                0 to 0.9f,
                2 to OCR_CHARACTER_SCORE_THRESHOLD,
            )
        )

        val accepted = decoded.characters
            .filter { character -> character.confidence >= OCR_CHARACTER_SCORE_THRESHOLD }
            .joinToString("") { character -> character.text }

        assertEquals("B", accepted)
    }

    @Test
    fun characterCellsFillTheDetectedLineBetweenNeighborCenters() {
        val decoded = decode(
            winners = listOf(
                0 to 0.9f,
                1 to 0.9f,
                0 to 0.9f,
                0 to 0.9f,
                2 to 0.9f,
                0 to 0.9f,
            )
        )

        assertEquals(0f, decoded.characters[0].startFraction, 0.001f)
        assertEquals(0.5f, decoded.characters[0].endFraction, 0.001f)
        assertEquals(0.5f, decoded.characters[1].startFraction, 0.001f)
        assertEquals(1f, decoded.characters[1].endFraction, 0.001f)
    }

    @Test
    fun singleCharacterCellUsesTheWholeDetectedLine() {
        val decoded = decode(
            winners = listOf(
                0 to 0.9f,
                1 to 0.9f,
                0 to 0.9f,
                0 to 0.9f,
            )
        )

        assertEquals(0f, decoded.characters.single().startFraction, 0.001f)
        assertEquals(1f, decoded.characters.single().endFraction, 0.001f)
    }

    private fun decode(winners: List<Pair<Int, Float>>): CTCDecoder.DecodedText =
        CTCDecoder.decode(
            output = logits(winners),
            shape = longArrayOf(1, winners.size.toLong(), 3),
            characterList = listOf("A", "B"),
        ).single()

    private fun logits(winners: List<Pair<Int, Float>>): FloatArray =
        FloatArray(winners.size * 3) { 0.01f }.also { output ->
            winners.forEachIndexed { timeStep, (classIndex, confidence) ->
                output[timeStep * 3 + classIndex] = confidence
            }
        }
}
