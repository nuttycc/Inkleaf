// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.engine

import com.paddle.ocr.model.OCRTextOrientation
import com.paddle.ocr.postprocess.CTCDecoder
import com.paddle.ocr.preprocess.RecPreprocessor
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.ceil

class RecognitionEngine(
    private val ortManager: ORTSessionManager,
    private val characterList: List<String>,
    private val imageMode: String,
    private val characterScoreThreshold: Float,
) {
    private data class DirectionScore(
        val acceptedCount: Int,
        val acceptedConfidence: Float,
        val rawConfidence: Float,
    )
    data class RecognizedCharacter(
        val text: String,
        val confidence: Float,
        val startFraction: Float,
        val endFraction: Float,
    )

    data class RecognizedText(
        val text: String,
        val confidence: Float,
        val characters: List<RecognizedCharacter>,
        val orientation: OCRTextOrientation,
    )

    data class RecognitionResult(
        val texts: List<RecognizedText>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    fun recognize(crops: List<Mat>): RecognitionResult {
        // Preprocess
        val preStart = System.currentTimeMillis()
        val preResult = RecPreprocessor.preprocessBatch(crops, imageMode)
        val preprocessMs = System.currentTimeMillis() - preStart

        // Inference
        val infStart = System.currentTimeMillis()
        val (outputData, outputShape) = ortManager.runRecognition(preResult.tensorData, preResult.shape)
        val inferenceMs = System.currentTimeMillis() - infStart

        // Postprocess (CTC decode)
        val postStart = System.currentTimeMillis()
        val outputTimeSteps = outputShape[1].toInt()
        val paddedWidth = preResult.shape[3].toInt().coerceAtLeast(1)
        val validTimeSteps = IntArray(preResult.resizedWidths.size) { index ->
            ceil(outputTimeSteps * preResult.resizedWidths[index].toDouble() / paddedWidth)
                .toInt()
                .coerceIn(1, outputTimeSteps)
        }
        val decoded = CTCDecoder.decode(
            output = outputData,
            shape = outputShape,
            characterList = characterList,
            validTimeSteps = validTimeSteps,
        ).map { item -> item.toRecognizedText(OCRTextOrientation.HORIZONTAL) }
        val postprocessMs = System.currentTimeMillis() - postStart

        val inputShape = preResult.shape.map { it.toInt() }
        val timeMs = preprocessMs + inferenceMs + postprocessMs
        return RecognitionResult(
            texts = decoded,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = timeMs,
            inputShape = inputShape,
        )
    }

    fun recognizeVertical(crop: Mat): RecognitionResult {
        val clockwise = Mat()
        Core.rotate(crop, clockwise, Core.ROTATE_90_CLOCKWISE)
        val clockwiseResult = try {
            recognize(listOf(clockwise))
        } finally {
            clockwise.release()
        }

        val counterClockwise = Mat()
        Core.rotate(crop, counterClockwise, Core.ROTATE_90_COUNTERCLOCKWISE)
        val counterClockwiseResult = try {
            recognize(listOf(counterClockwise))
        } finally {
            counterClockwise.release()
        }

        val clockwiseScore = clockwiseResult.directionScore()
        val counterClockwiseScore = counterClockwiseResult.directionScore()
        val chooseClockwise = when {
            clockwiseScore.acceptedCount != counterClockwiseScore.acceptedCount ->
                clockwiseScore.acceptedCount > counterClockwiseScore.acceptedCount
            clockwiseScore.acceptedConfidence != counterClockwiseScore.acceptedConfidence ->
                clockwiseScore.acceptedConfidence > counterClockwiseScore.acceptedConfidence
            else -> clockwiseScore.rawConfidence >= counterClockwiseScore.rawConfidence
        }
        val chosen = if (chooseClockwise) {
            clockwiseResult.copy(
                texts = clockwiseResult.texts.map { text ->
                    val normalizedCharacters = text.characters
                        .map { character ->
                            character.copy(
                                startFraction = 1f - character.endFraction,
                                endFraction = 1f - character.startFraction,
                            )
                        }
                        .sortedBy(RecognizedCharacter::startFraction)
                    text.copy(
                        text = normalizedCharacters.joinToString("") { it.text },
                        characters = normalizedCharacters,
                        orientation = OCRTextOrientation.VERTICAL,
                    )
                },
            )
        } else {
            counterClockwiseResult.copy(
                texts = counterClockwiseResult.texts.map { text ->
                    text.copy(orientation = OCRTextOrientation.VERTICAL)
                },
            )
        }
        return chosen.copy(
            preprocessMs = clockwiseResult.preprocessMs + counterClockwiseResult.preprocessMs,
            inferenceMs = clockwiseResult.inferenceMs + counterClockwiseResult.inferenceMs,
            postprocessMs = clockwiseResult.postprocessMs + counterClockwiseResult.postprocessMs,
            timeMs = clockwiseResult.timeMs + counterClockwiseResult.timeMs,
        )
    }

    private fun RecognitionResult.directionScore(): DirectionScore {
        val accepted = texts
            .flatMap(RecognizedText::characters)
            .filter { character ->
                character.confidence >= characterScoreThreshold && character.text.isNotBlank()
            }
        return DirectionScore(
            acceptedCount = accepted.size,
            acceptedConfidence = if (accepted.isEmpty()) {
                0f
            } else {
                accepted.map(RecognizedCharacter::confidence).average().toFloat()
            },
            rawConfidence = texts.firstOrNull()?.confidence ?: 0f,
        )
    }

    private fun CTCDecoder.DecodedText.toRecognizedText(
        orientation: OCRTextOrientation,
    ): RecognizedText = RecognizedText(
        text = text,
        confidence = confidence,
        characters = characters.map { character ->
            RecognizedCharacter(
                text = character.text,
                confidence = character.confidence,
                startFraction = character.startFraction,
                endFraction = character.endFraction,
            )
        },
        orientation = orientation,
    )
}
