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

package com.paddle.ocr.postprocess

object CTCDecoder {
    private const val BLANK_IDX = 0

    data class DecodedCharacter(
        val text: String,
        val confidence: Float,
        val startFraction: Float,
        val endFraction: Float,
    )

    data class DecodedText(
        val text: String,
        val confidence: Float,
        val characters: List<DecodedCharacter>,
    )

    private data class CharacterRun(
        val text: String,
        val confidence: Float,
        val startStep: Int,
        val endStep: Int,
    )

    fun decode(
        output: FloatArray,
        shape: LongArray,
        characterList: List<String>,
        validTimeSteps: IntArray? = null,
    ): List<DecodedText> {
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()

        val results = mutableListOf<DecodedText>()
        for (b in 0 until batchSize) {
            val baseOffset = b * timeSteps * numClasses
            val sampleTimeSteps = validTimeSteps?.getOrNull(b)?.coerceIn(1, timeSteps) ?: timeSteps

            val indices = IntArray(sampleTimeSteps)
            val probs = FloatArray(sampleTimeSteps)
            for (t in 0 until sampleTimeSteps) {
                val offset = baseOffset + t * numClasses
                var maxIdx = 0
                var maxVal = output[offset]
                for (c in 1 until numClasses) {
                    val v = output[offset + c]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }
                indices[t] = maxIdx
                probs[t] = maxVal
            }

            val runs = mutableListOf<CharacterRun>()
            var activeIndex = BLANK_IDX
            var activeStart = 0
            var activeConfidence = 0f

            fun finishRun(endStep: Int) {
                if (activeIndex == BLANK_IDX) return
                val characterIndex = activeIndex - 1
                val token = characterList.getOrNull(characterIndex) ?: return
                runs +=
                    CharacterRun(
                        text = token,
                        confidence = activeConfidence,
                        startStep = activeStart,
                        endStep = endStep,
                    )
            }

            for (t in 0 until sampleTimeSteps) {
                val idx = indices[t]
                if (idx != activeIndex) {
                    finishRun(t)
                    activeIndex = idx
                    activeStart = t
                    activeConfidence = probs[t]
                }
            }
            finishRun(sampleTimeSteps)

            val timedCharacters = runs.flatMapIndexed { index, run ->
                val startStep =
                    if (index == 0) {
                        run.startStep.toFloat()
                    } else {
                        (runs[index - 1].endStep + run.startStep) / 2f
                    }
                val endStep =
                    if (index == runs.lastIndex) {
                        run.endStep.toFloat()
                    } else {
                        (run.endStep + runs[index + 1].startStep) / 2f
                    }
                val tokens = splitToken(run.text)
                tokens.mapIndexedNotNull { tokenIndex, token ->
                    if (token.isBlank()) return@mapIndexedNotNull null
                    val tokenStart = startStep + (endStep - startStep) * tokenIndex / tokens.size
                    val tokenEnd =
                        startStep + (endStep - startStep) * (tokenIndex + 1) / tokens.size
                    DecodedCharacter(
                        text = token,
                        confidence = run.confidence,
                        startFraction = (tokenStart / sampleTimeSteps).coerceIn(0f, 1f),
                        endFraction = (tokenEnd / sampleTimeSteps).coerceIn(0f, 1f),
                    )
                }
            }
            val decodedCharacters = assignCharacterCells(timedCharacters)
            val confidence =
                if (decodedCharacters.isEmpty()) {
                    0f
                } else {
                    decodedCharacters.map(DecodedCharacter::confidence).average().toFloat()
                }
            results +=
                DecodedText(
                    text = decodedCharacters.joinToString("") { it.text },
                    confidence = confidence,
                    characters = decodedCharacters,
                )
        }
        return results
    }

    private fun assignCharacterCells(characters: List<DecodedCharacter>): List<DecodedCharacter> {
        if (characters.isEmpty()) return emptyList()
        if (characters.size == 1) {
            return listOf(characters.single().copy(startFraction = 0f, endFraction = 1f))
        }

        val centers = characters.map { character ->
            (character.startFraction + character.endFraction) / 2f
        }
        val boundaries = FloatArray(characters.size + 1)
        boundaries[0] = 0f
        for (index in 1 until characters.size) {
            boundaries[index] =
                ((centers[index - 1] + centers[index]) / 2f).coerceIn(boundaries[index - 1], 1f)
        }
        boundaries[characters.size] = 1f

        // CTC activations locate character centers, not glyph edges. Partition the complete
        // detector line at neighboring center midpoints so each projected box covers the glyph.
        return characters.mapIndexed { index, character ->
            character.copy(
                startFraction = boundaries[index],
                endFraction = boundaries[index + 1],
            )
        }
    }

    private fun splitToken(token: String): List<String> =
        token.codePoints().toArray().map { codePoint -> String(Character.toChars(codePoint)) }
}
