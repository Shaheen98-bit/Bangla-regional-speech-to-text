package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Manages 16 kHz Mono 16-bit PCM microphone capture using AudioRecord.
 * Maintains a rolling audio window (e.g. 2.5 - 3.0 seconds) and periodically
 * yields audio windows for inference while broadcasting RMS amplitude for UI visuals.
 */
class AudioRecorderManager(
    val sampleRate: Int = 16000,
    val windowDurationMs: Int = 2500, // 2.5 second rolling window
    val stepDurationMs: Int = 500     // 0.5 second update step interval
) {
    companion object {
        private const val TAG = "AudioRecorderManager"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    val windowSamplesCount: Int = (sampleRate * (windowDurationMs / 1000.0)).toInt() // e.g. 40,000 samples
    val stepSamplesCount: Int = (sampleRate * (stepDurationMs / 1000.0)).toInt()     // e.g. 8,000 samples

    // Rolling audio buffer of PCM samples
    private val rollingBuffer = ShortArray(windowSamplesCount)
    private var samplesAccumulated = 0
    private var samplesSinceLastEmit = 0

    interface AudioListener {
        fun onAudioChunkAvailable(audioWindow: ShortArray, totalDurationMs: Long)
        fun onAmplitudeChanged(rmsNormalized: Float)
        fun onError(message: String)
    }

    private var listener: AudioListener? = null

    fun setListener(listener: AudioListener) {
        this.listener = listener
    }

    fun isRecordingActive(): Boolean = isRecording

    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (isRecording) return true

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            listener?.onError("AudioRecord configuration not supported on this device.")
            return false
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(sampleRate / 2) // At least 0.5s buffer

        var record: AudioRecord? = null

        // Try VOICE_RECOGNITION first, fallback to MIC
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sourcesToTry) {
            try {
                val candidate = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize AudioRecord with source: $source", e)
            }
        }

        if (record == null) {
            listener?.onError("Failed to initialize microphone with 16kHz mono PCM.")
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            listener?.onError("Failed to start AudioRecord: ${e.localizedMessage}")
            return false
        }

        audioRecord = record
        isRecording = true
        samplesAccumulated = 0
        samplesSinceLastEmit = 0
        rollingBuffer.fill(0)

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val readBuffer = ShortArray(1024) // 64ms chunks

            while (isActive && isRecording) {
                val readCount = record.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    processIncomingPcm(readBuffer, readCount)
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                }
            }
        }

        return true
    }

    private fun processIncomingPcm(samples: ShortArray, count: Int) {
        // Calculate RMS amplitude for UI visualization
        var sumSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / count)
        val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        listener?.onAmplitudeChanged(normalizedRms)

        // Shift and append to rolling buffer
        if (count >= windowSamplesCount) {
            System.arraycopy(samples, count - windowSamplesCount, rollingBuffer, 0, windowSamplesCount)
            samplesAccumulated = windowSamplesCount
        } else {
            val shiftAmount = count
            val keepAmount = windowSamplesCount - shiftAmount
            System.arraycopy(rollingBuffer, shiftAmount, rollingBuffer, 0, keepAmount)
            System.arraycopy(samples, 0, rollingBuffer, keepAmount, count)
            samplesAccumulated = (samplesAccumulated + count).coerceAtMost(windowSamplesCount)
        }

        samplesSinceLastEmit += count

        // When step duration has accumulated and we have enough window data, emit window
        if (samplesSinceLastEmit >= stepSamplesCount && samplesAccumulated >= (sampleRate * 0.8)) {
            val windowCopy = rollingBuffer.copyOf()
            val durationMs = (samplesAccumulated * 1000L) / sampleRate
            listener?.onAudioChunkAvailable(windowCopy, durationMs)
            samplesSinceLastEmit = 0
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }
        listener?.onAmplitudeChanged(0f)
    }
}
