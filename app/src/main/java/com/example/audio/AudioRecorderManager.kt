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
 * Collects speech utterances and provides real-time audio windows for ASR inference.
 */
class AudioRecorderManager(
    val sampleRate: Int = 16000,
    val maxUtteranceDurationMs: Int = 12000, // Up to 12s continuous utterance
    val stepDurationMs: Int = 400            // 400ms update step interval for responsive streaming
) {
    companion object {
        private const val TAG = "AudioRecorderManager"
        private const val SILENCE_RMS_THRESHOLD = 0.003f
        private const val PAUSE_COMMIT_DURATION_MS = 1400L // 1.4s pause triggers utterance commit
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private val maxUtteranceSamples: Int = (sampleRate * (maxUtteranceDurationMs / 1000.0)).toInt() // 192,000 samples
    private val stepSamplesCount: Int = (sampleRate * (stepDurationMs / 1000.0)).toInt()            // 6,400 samples

    // Utterance buffer holding ONLY valid captured PCM samples for the current sentence
    private val utteranceBuffer = ShortArray(maxUtteranceSamples)
    private var currentUtteranceSamples = 0
    private var samplesSinceLastEmit = 0
    private var silentSamplesCount = 0
    private var speechDetectedInUtterance = false

    interface AudioListener {
        fun onAudioChunkAvailable(audioWindow: ShortArray, totalDurationMs: Long, isEndOfUtterance: Boolean)
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
        resetUtteranceState()

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

    private fun resetUtteranceState() {
        currentUtteranceSamples = 0
        samplesSinceLastEmit = 0
        silentSamplesCount = 0
        speechDetectedInUtterance = false
    }

    private fun processIncomingPcm(samples: ShortArray, count: Int) {
        // Calculate RMS amplitude for UI visualization & VAD
        var sumSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / count)
        val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        listener?.onAmplitudeChanged(normalizedRms)

        val isChunkSpeech = normalizedRms >= SILENCE_RMS_THRESHOLD

        if (isChunkSpeech) {
            speechDetectedInUtterance = true
            silentSamplesCount = 0
        } else {
            silentSamplesCount += count
        }

        // If we haven't detected speech yet in this utterance and buffer is silent,
        // keep only the last ~0.3s as lead-in to avoid leading silence buildup
        if (!speechDetectedInUtterance && currentUtteranceSamples > (sampleRate * 0.3)) {
            val leadIn = (sampleRate * 0.3).toInt()
            System.arraycopy(samples, 0, utteranceBuffer, 0, count.coerceAtMost(leadIn))
            currentUtteranceSamples = count.coerceAtMost(leadIn)
            return
        }

        // Append incoming samples to utterance buffer
        val spaceAvailable = maxUtteranceSamples - currentUtteranceSamples
        if (spaceAvailable >= count) {
            System.arraycopy(samples, 0, utteranceBuffer, currentUtteranceSamples, count)
            currentUtteranceSamples += count
        } else {
            // Buffer full: slide left by half to keep recent context
            val half = maxUtteranceSamples / 2
            System.arraycopy(utteranceBuffer, half, utteranceBuffer, 0, half)
            System.arraycopy(samples, 0, utteranceBuffer, half, count.coerceAtMost(maxUtteranceSamples - half))
            currentUtteranceSamples = half + count.coerceAtMost(maxUtteranceSamples - half)
        }

        samplesSinceLastEmit += count

        // Check for pause / end-of-sentence condition
        val pauseDurationMs = (silentSamplesCount * 1000L) / sampleRate
        val isPauseAfterSpeech = speechDetectedInUtterance && (pauseDurationMs >= PAUSE_COMMIT_DURATION_MS)

        if (isPauseAfterSpeech && currentUtteranceSamples >= (sampleRate * 0.8)) {
            // Emit final chunk of this utterance with isEndOfUtterance = true
            val utteranceCopy = utteranceBuffer.copyOfRange(0, currentUtteranceSamples)
            val durationMs = (currentUtteranceSamples * 1000L) / sampleRate
            listener?.onAudioChunkAvailable(utteranceCopy, durationMs, isEndOfUtterance = true)
            resetUtteranceState()
            return
        }

        // Periodic streaming emission during active speaking
        val minSamplesForInference = (sampleRate * 0.4).toInt() // At least 400ms
        if (samplesSinceLastEmit >= stepSamplesCount && currentUtteranceSamples >= minSamplesForInference) {
            val utteranceCopy = utteranceBuffer.copyOfRange(0, currentUtteranceSamples)
            val durationMs = (currentUtteranceSamples * 1000L) / sampleRate
            listener?.onAudioChunkAvailable(utteranceCopy, durationMs, isEndOfUtterance = false)
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
        resetUtteranceState()
    }
}
