package com.example.engine

import android.util.Log
import com.example.tokenizer.SentencePieceTokenizer

/**
 * Greedy CTC Decoder for 129-class NeMo Conformer STT outputs.
 * Takes 3D logprobs array [1, T, 129], performs argmax, removes repeats and blank tokens,
 * then maps to Bengali text via SentencePiece tokenizer.
 */
class CtcDecoder(
    var blankIndex: Int = 128
) {
    companion object {
        private const val TAG = "CtcDecoder"
    }

    data class DecodeResult(
        val text: String,
        val tokenIds: List<Int>,
        val rawArgmax: IntArray,
        val collapsedIds: List<Int>,
        val pieces: List<String>
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
            return DecodeResult("", emptyList(), IntArray(0), emptyList(), emptyList())
        }

        val rawArgmax = IntArray(numFrames)

        // 1. Argmax for each timestep over output classes
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

        // 2. CTC collapse: collapse consecutive identical predictions, then filter out blanks
        val collapsedIds = ArrayList<Int>()
        var prevId = -1

        for (t in 0 until numFrames) {
            val currentId = rawArgmax[t]
            if (currentId != prevId) {
                if (currentId != blankIndex) {
                    collapsedIds.add(currentId)
                }
                prevId = currentId
            }
        }

        // 3. Map token IDs to pieces for diagnostics
        val pieces = ArrayList<String>()
        if (tokenizer != null) {
            for (id in collapsedIds) {
                val piece = tokenizer.getPiece(id) ?: "<id_$id>"
                pieces.add(piece)
            }
        }

        // 4. Decode using SentencePiece
        val decodedText = tokenizer?.decode(collapsedIds) ?: ""

        return DecodeResult(
            text = decodedText,
            tokenIds = collapsedIds,
            rawArgmax = rawArgmax,
            collapsedIds = collapsedIds,
            pieces = pieces
        )
    }

    /**
     * Diagnostic helper: Takes raw token IDs and prints full diagnostic breakdown.
     */
    fun printDiagnostic(
        rawArgmax: IntArray,
        collapsedIds: List<Int>,
        pieces: List<String>,
        decodedText: String
    ) {
        val rawStr = if (rawArgmax.size > 50) {
            rawArgmax.take(50).joinToString(", ") + "... (total ${rawArgmax.size})"
        } else {
            rawArgmax.joinToString(", ")
        }

        Log.d(TAG, "RAW IDS:\n[$rawStr]")
        Log.d(TAG, "COLLAPSED IDS:\n$collapsedIds")
        Log.d(TAG, "TOKENS:\n$pieces")
        Log.d(TAG, "DECODED:\n\"$decodedText\"")
    }

    /**
     * Determines optimal blank index based on tokenizer vocab size and model numClasses.
     */
    fun updateBlankIndexFromVocab(vocabSize: Int, numClasses: Int) {
        blankIndex = if (numClasses == vocabSize + 1) {
            vocabSize // Standard NeMo blank index (last class index, e.g. 128 for 128 vocab)
        } else if (numClasses == 129 && vocabSize <= 128) {
            128
        } else {
            numClasses - 1
        }
        Log.i(TAG, "CTC Blank Index configured: $blankIndex (vocabSize=$vocabSize, numClasses=$numClasses)")
    }
}
