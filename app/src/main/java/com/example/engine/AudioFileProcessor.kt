package com.example.engine

import android.util.Log
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.tokenizer.SentencePieceTokenizer
import kotlin.math.sqrt

/**
 * Handles batch audio file transcription using:
 * - Whole-stream VAD & speech utterance boundary segmentation
 * - Sample-accurate PCM slicing (never re-processing or re-committing the same speech region)
 * - Acoustic preprocessing & ONNX CTC inference per utterance
 * - Unified TranscriptAccumulator for single-source-of-truth accumulation and overlap deduplication
 */
class AudioFileProcessor(
    private val preprocessor: MelSpectrogramPreprocessor,
    private val onnxEngine: OnnxAsrEngine,
    private val ctcDecoder: CtcDecoder
) {
    companion object {
        private const val TAG = "AudioFileProcessor"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_SAMPLES = 480 // 30ms at 16kHz
        private const val SILENCE_RMS_THRESHOLD = 0.003f
        private const val MIN_UTTERANCE_SAMPLES = 6400 // 400ms
        private const val MAX_UTTERANCE_SAMPLES = 128000 // 8.0s
        private const val MAX_SILENCE_MERGE_SAMPLES = 9600 // 600ms
    }

    data class SpeechUtterance(
        val utteranceId: Long,
        val startSample: Int,
        val endSample: Int,
        var committed: Boolean = false
    ) {
        val startMs: Long get() = (startSample * 1000L) / SAMPLE_RATE
        val endMs: Long get() = (endSample * 1000L) / SAMPLE_RATE
    }

    /**
     * Segments continuous audio into distinct speech utterances using energy-based VAD.
     */
    fun segmentUtterances(samples: ShortArray): List<SpeechUtterance> {
        val totalSamples = samples.size
        if (totalSamples < MIN_UTTERANCE_SAMPLES) {
            return listOf(SpeechUtterance(1L, 0, totalSamples))
        }

        val numFrames = totalSamples / FRAME_SIZE_SAMPLES
        val isSpeechFrame = BooleanArray(numFrames)

        // 1. Calculate RMS for each 30ms frame
        for (f in 0 until numFrames) {
            val offset = f * FRAME_SIZE_SAMPLES
            var sumSquares = 0.0
            for (i in 0 until FRAME_SIZE_SAMPLES) {
                val s = samples[offset + i].toDouble()
                sumSquares += s * s
            }
            val rms = sqrt(sumSquares / FRAME_SIZE_SAMPLES) / 32768.0
            isSpeechFrame[f] = rms >= SILENCE_RMS_THRESHOLD
        }

        // 2. Group contiguous speech frames into raw segments
        val rawSegments = mutableListOf<Pair<Int, Int>>() // frame start to frame end
        var inSpeech = false
        var segStartFrame = 0

        for (f in 0 until numFrames) {
            if (isSpeechFrame[f] && !inSpeech) {
                inSpeech = true
                segStartFrame = f
            } else if (!isSpeechFrame[f] && inSpeech) {
                inSpeech = false
                rawSegments.add(Pair(segStartFrame, f))
            }
        }
        if (inSpeech) {
            rawSegments.add(Pair(segStartFrame, numFrames))
        }

        // If no speech detected by threshold, split evenly into 6-second windows
        if (rawSegments.isEmpty()) {
            val fallbackUtterances = mutableListOf<SpeechUtterance>()
            var cur = 0
            var id = 1L
            val step = SAMPLE_RATE * 6
            while (cur < totalSamples) {
                val end = (cur + step).coerceAtMost(totalSamples)
                fallbackUtterances.add(SpeechUtterance(id++, cur, end))
                cur += step
            }
            return fallbackUtterances
        }

        // 3. Merge segments separated by small silence (< 600ms)
        val mergedFrames = mutableListOf<Pair<Int, Int>>()
        var current = rawSegments.first()

        for (i in 1 until rawSegments.size) {
            val next = rawSegments[i]
            val silenceFrames = next.first - current.second
            val silenceSamples = silenceFrames * FRAME_SIZE_SAMPLES

            if (silenceSamples < MAX_SILENCE_MERGE_SAMPLES) {
                current = Pair(current.first, next.second)
            } else {
                mergedFrames.add(current)
                current = next
            }
        }
        mergedFrames.add(current)

        // 4. Convert frame boundaries to sample boundaries with padding & max duration limit
        val utterances = mutableListOf<SpeechUtterance>()
        var idCounter = 1L
        val padSamples = (SAMPLE_RATE * 0.15).toInt() // 150ms padding

        for (pair in mergedFrames) {
            val startSample = (pair.first * FRAME_SIZE_SAMPLES - padSamples).coerceAtLeast(0)
            val endSample = (pair.second * FRAME_SIZE_SAMPLES + padSamples).coerceAtMost(totalSamples)
            val segLen = endSample - startSample

            if (segLen < MIN_UTTERANCE_SAMPLES) {
                continue
            }

            if (segLen <= MAX_UTTERANCE_SAMPLES) {
                utterances.add(SpeechUtterance(idCounter++, startSample, endSample))
            } else {
                // Split long utterance into max 6s chunks
                var chunkStart = startSample
                while (chunkStart < endSample) {
                    val chunkEnd = (chunkStart + SAMPLE_RATE * 6).coerceAtMost(endSample)
                    if (chunkEnd - chunkStart >= MIN_UTTERANCE_SAMPLES) {
                        utterances.add(SpeechUtterance(idCounter++, chunkStart, chunkEnd))
                    }
                    chunkStart = chunkEnd
                }
            }
        }

        return utterances.ifEmpty {
            listOf(SpeechUtterance(1L, 0, totalSamples))
        }
    }

    /**
     * Transcribes an entire decoded audio stream using the provided TranscriptAccumulator.
     * Yields progress and returns the list of finalized TranscriptSegments.
     */
    suspend fun transcribeAudio(
        samples: ShortArray,
        tokenizer: SentencePieceTokenizer?,
        accumulator: TranscriptAccumulator = TranscriptAccumulator(),
        onProgress: (progress: Float, currentSegment: TranscriptSegment?, fullText: String) -> Unit
    ): List<TranscriptSegment> {
        val utterances = segmentUtterances(samples)
        val totalUtterances = utterances.size

        for ((index, utterance) in utterances.withIndex()) {
            if (utterance.committed) continue

            val audioChunk = samples.copyOfRange(utterance.startSample, utterance.endSample)
            val prepResult = preprocessor.process(audioChunk)

            var recognizedText = ""
            if (prepResult.numFrames > 0 && !prepResult.isSilence) {
                val inferResult = onnxEngine.runInference(prepResult)
                if (inferResult.isSuccess) {
                    val inf = inferResult.getOrThrow()
                    val decodeResult = ctcDecoder.decode(
                        logprobs = inf.logprobs,
                        numFrames = inf.numFrames,
                        numClasses = inf.numClasses,
                        tokenizer = tokenizer
                    )
                    recognizedText = decodeResult.text
                }
            }

            utterance.committed = true

            // Set interim text then commit utterance to the unified accumulator
            if (recognizedText.isNotBlank()) {
                accumulator.updateLive(recognizedText)
            }

            val committedSegment = accumulator.commitFinal(
                rawText = recognizedText,
                utteranceId = utterance.utteranceId,
                startMs = utterance.startMs,
                endMs = utterance.endMs
            )

            val currentFullText = accumulator.finalTranscript
            val progress = 0.5f + (((index + 1).toFloat() / totalUtterances.toFloat()) * 0.5f)

            onProgress(progress.coerceIn(0.5f, 1.0f), committedSegment, currentFullText)
        }

        // Flush any remaining live partial text at the end of the file
        accumulator.flushOnStop()

        return accumulator.finalizedSegments
    }
}
