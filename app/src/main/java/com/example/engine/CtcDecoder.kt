package com.example.engine

import com.example.tokenizer.SentencePieceTokenizer

/**
 * Greedy CTC Decoder for 129-class NeMo Conformer STT outputs.
 * Takes 3D logprobs array [1, T, 129], performs argmax, removes repeats and blank tokens,
 * then maps to Bengali text via SentencePiece tokenizer.
 */
class CtcDecoder(
    var blankIndex: Int = 128
) {
    data class DecodeResult(
        val text: String,
        val tokenIds: List<Int>,
        val rawArgmax: IntArray
    )

    /**
     * Decodes flattened logprobs float array of shape [1, numFrames, numClasses].
     * @param logprobs Flat float array of size 1 * numFrames * numClasses
     * @param numFrames T dimension
     * @param numClasses 129 dimension
     * @param tokenizer SentencePieceTokenizer
     */
    fun decode(
        logprobs: FloatArray,
        numFrames: Int,
        numClasses: Int,
        tokenizer: SentencePieceTokenizer?
    ): DecodeResult {
        if (numFrames <= 0 || numClasses <= 0 || logprobs.size < numFrames * numClasses) {
            return DecodeResult("", emptyList(), IntArray(0))
        }

        val rawArgmax = IntArray(numFrames)

        // 1. Argmax for each timestep
        for (t in 0 until numFrames) {
            val frameOffset = t * numClasses
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = 0

            for (c in 0 until numClasses) {
                val score = logprobs[frameOffset + c]
                if (score > maxVal) {
                    maxVal = score
                    maxIdx = c
                }
            }
            rawArgmax[t] = maxIdx
        }

        // 2. Collapse consecutive duplicate IDs and remove blank
        val tokenIds = ArrayList<Int>()
        var prevId = -1

        for (t in 0 until numFrames) {
            val currentId = rawArgmax[t]
            if (currentId != prevId) {
                if (currentId != blankIndex && (blankIndex >= 0)) {
                    tokenIds.add(currentId)
                }
                prevId = currentId
            }
        }

        // 3. SentencePiece decode
        val decodedText = tokenizer?.decode(tokenIds) ?: ""

        return DecodeResult(
            text = decodedText,
            tokenIds = tokenIds,
            rawArgmax = rawArgmax
        )
    }

    /**
     * Determines optimal blank index based on tokenizer vocab size and model numClasses.
     */
    fun updateBlankIndexFromVocab(vocabSize: Int, numClasses: Int) {
        if (numClasses == 129 && vocabSize <= 128) {
            blankIndex = 128 // Standard NeMo blank index (last token)
        } else if (blankIndex >= numClasses) {
            blankIndex = numClasses - 1
        }
    }
}
