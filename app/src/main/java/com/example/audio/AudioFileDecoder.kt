package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes various audio formats (MP3, AAC/M4A, WAV, OGG, FLAC, MP4/3GP audio tracks)
 * from Android content URIs into 16 kHz 16-bit Mono PCM samples for offline STT.
 */
object AudioFileDecoder {
    private const val TAG = "AudioFileDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10000L

    data class DecodedAudio(
        val samples: ShortArray,
        val durationMs: Long,
        val originalSampleRate: Int,
        val originalChannels: Int
    )

    /**
     * Extracts and decodes audio from a content Uri into a 16kHz mono 16-bit PCM ShortArray.
     */
    fun decodeAudioUri(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting data source for URI: $uri", e)
            throw IllegalArgumentException("Could not open audio file: ${e.localizedMessage}")
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found in the selected file.")
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val originalSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val originalChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        Log.i(TAG, "Found audio track: mime=$mime, sampleRate=$originalSampleRate, channels=$originalChannels, duration=${durationUs / 1000}ms")

        val decoder: MediaCodec
        try {
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("Failed to initialize audio decoder for format $mime: ${e.localizedMessage}")
        }

        val rawPcmBuffer = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var isExtractorEOS = false
        var isDecoderEOS = false

        var actualSampleRate = originalSampleRate
        var actualChannels = originalChannels

        try {
            while (!isDecoderEOS) {
                if (!isExtractorEOS) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()

                                if (durationUs > 0) {
                                    val progress = (sampleTime.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.9f)
                                    onProgress(progress * 0.5f) // Decoding is 0% to 50% of total audio prep
                                }
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        rawPcmBuffer.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        actualSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        actualChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    Log.i(TAG, "Audio decoder output format changed: sampleRate=$actualSampleRate, channels=$actualChannels")
                }
            }
        } finally {
            try {
                decoder.stop()
                decoder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing decoder", e)
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing extractor", e)
            }
        }

        val rawPcmBytes = rawPcmBuffer.toByteArray()
        if (rawPcmBytes.isEmpty()) {
            throw IllegalStateException("Decoded audio is empty.")
        }

        // Convert PCM bytes to 16-bit short samples
        val shortBuffer = ByteBuffer.wrap(rawPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val decodedShorts = ShortArray(shortBuffer.remaining())
        shortBuffer.get(decodedShorts)

        // 1. Channel downmixing to Mono if necessary
        val monoShorts: ShortArray = if (actualChannels > 1) {
            val monoLength = decodedShorts.size / actualChannels
            val mono = ShortArray(monoLength)
            for (i in 0 until monoLength) {
                var sum = 0
                for (ch in 0 until actualChannels) {
                    sum += decodedShorts[i * actualChannels + ch]
                }
                mono[i] = (sum / actualChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            mono
        } else {
            decodedShorts
        }

        // 2. Resample to 16000 Hz if necessary (Linear Interpolation)
        val resampledShorts: ShortArray = if (actualSampleRate != TARGET_SAMPLE_RATE && actualSampleRate > 0) {
            val ratio = TARGET_SAMPLE_RATE.toDouble() / actualSampleRate.toDouble()
            val targetLength = (monoShorts.size * ratio).toInt()
            val resampled = ShortArray(targetLength)

            for (i in 0 until targetLength) {
                val origIdx = i / ratio
                val left = origIdx.toInt()
                val right = (left + 1).coerceAtMost(monoShorts.size - 1)
                val frac = origIdx - left

                val sample = ((1.0 - frac) * monoShorts[left] + frac * monoShorts[right]).toInt()
                resampled[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            resampled
        } else {
            monoShorts
        }

        val totalDurationMs = (resampledShorts.size * 1000L) / TARGET_SAMPLE_RATE

        return DecodedAudio(
            samples = resampledShorts,
            durationMs = totalDurationMs,
            originalSampleRate = actualSampleRate,
            originalChannels = actualChannels
        )
    }
}
