package com.example.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Exact NeMo-compatible AudioToMelSpectrogramPreprocessor implementation.
 *
 * Parameters matching NeMo YAML:
 * sample_rate: 16000
 * normalize: per_feature
 * window_size: 0.025 (400 samples)
 * window_stride: 0.01 (160 samples)
 * window: hann
 * features: 80
 * n_fft: 512
 * log: true (ln(mel + 1e-5))
 * frame_splicing: 1
 * dither: 1.0e-05
 * pad_to: 0
 * pad_value: 0.0
 */
class MelSpectrogramPreprocessor(
    val sampleRate: Int = 16000,
    val windowSizeSec: Float = 0.025f,
    val windowStrideSec: Float = 0.01f,
    val nMels: Int = 80,
    val nFft: Int = 512,
    val dither: Float = 1.0e-05f,
    val logEps: Float = 1.0e-05f
) {
    val windowLength: Int = (sampleRate * windowSizeSec).toInt() // 400
    val hopLength: Int = (sampleRate * windowStrideSec).toInt()   // 160
    val numFreqBins: Int = nFft / 2 + 1                         // 257

    private val fft = FftRadix2(nFft)
    private val melFilterbank = MelFilterbank(
        nMels = nMels,
        nFft = nFft,
        sampleRate = sampleRate,
        fMin = 0f,
        fMax = (sampleRate / 2).toFloat()
    )

    // Precomputed Hann window (symmetric, length 400) matching torch.hann_window(400, periodic=False)
    private val hannWindow = FloatArray(windowLength) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (windowLength - 1)))).toFloat()
    }

    // Reusable buffers to minimize GC allocations during streaming inference
    private val fftReal = FloatArray(nFft)
    private val fftImag = FloatArray(nFft)
    private val powerSpectrum = FloatArray(numFreqBins)
    private val random = Random(42)

    data class PreprocessResult(
        val features: FloatArray, // [1, 80, T] flattened as m * T + t
        val numFrames: Int,       // T
        val nMels: Int = 80,
        val isSilence: Boolean = false
    ) {
        /**
         * Converts flattened float array to Direct FloatBuffer suitable for ONNX Runtime tensor.
         */
        fun toDirectFloatBuffer(): FloatBuffer {
            val byteBuffer = ByteBuffer.allocateDirect(features.size * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()
            floatBuffer.put(features)
            floatBuffer.flip()
            return floatBuffer
        }
    }

    /**
     * Preprocesses PCM 16-bit short samples into [1, 80, T] mel features.
     */
    fun process(pcmSamples: ShortArray): PreprocessResult {
        if (pcmSamples.isEmpty()) {
            return PreprocessResult(FloatArray(0), 0, nMels, isSilence = true)
        }
        val floatSamples = FloatArray(pcmSamples.size)
        var maxAbs = 0f
        for (i in pcmSamples.indices) {
            val f = pcmSamples[i].toFloat() / 32768.0f
            floatSamples[i] = f
            val absF = kotlin.math.abs(f)
            if (absF > maxAbs) maxAbs = absF
        }
        val isSilence = maxAbs < 0.002f // RMS / Peak is effectively background silence
        return process(floatSamples, isSilence)
    }

    /**
     * Preprocesses normalized float samples [-1.0, 1.0].
     */
    fun process(samples: FloatArray, isSilence: Boolean = false): PreprocessResult {
        if (samples.size < windowLength) {
            return PreprocessResult(FloatArray(0), 0, nMels, isSilence = true)
        }

        // Calculate number of frames T
        val numFrames = 1 + (samples.size - windowLength) / hopLength
        if (numFrames <= 0) {
            return PreprocessResult(FloatArray(0), 0, nMels, isSilence = true)
        }

        // Temporary storage for log mel spectrogram: [nMels, numFrames]
        val melSpectrogram = Array(nMels) { FloatArray(numFrames) }

        // Process frame by frame
        for (t in 0 until numFrames) {
            val frameStart = t * hopLength

            // Prepare FFT buffer with Hann window & dither
            fftReal.fill(0f)
            fftImag.fill(0f)

            for (n in 0 until windowLength) {
                var sample = samples[frameStart + n]
                if (dither > 0f) {
                    sample += (random.nextFloat() * 2f - 1f) * dither
                }
                fftReal[n] = sample * hannWindow[n]
            }

            // Execute Radix-2 512 FFT
            fft.transform(fftReal, fftImag)

            // Compute power spectrum: |X|^2 = real^2 + imag^2 for 257 bins
            for (k in 0 until numFreqBins) {
                powerSpectrum[k] = fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]
            }

            // Apply 80 Mel filters and compute log: ln(sum(w_k * power_k) + eps)
            for (m in 0 until nMels) {
                val weightsM = melFilterbank.weights[m]
                var melSum = 0.0f
                for (k in 0 until numFreqBins) {
                    val w = weightsM[k]
                    if (w > 0f) {
                        melSum += w * powerSpectrum[k]
                    }
                }
                melSpectrogram[m][t] = ln(melSum + logEps)
            }
        }

        // Per-feature normalization across time dimension (for each mel channel m)
        // normalized[m, t] = (mel[m, t] - mean[m]) / (std[m] + 1e-5)
        for (m in 0 until nMels) {
            var sum = 0.0
            for (t in 0 until numFrames) {
                sum += melSpectrogram[m][t]
            }
            val mean = (sum / numFrames).toFloat()

            var varSum = 0.0
            for (t in 0 until numFrames) {
                val diff = melSpectrogram[m][t] - mean
                varSum += diff * diff
            }
            val std = (sqrt(varSum / numFrames) + 1e-5).toFloat()

            for (t in 0 until numFrames) {
                melSpectrogram[m][t] = (melSpectrogram[m][t] - mean) / std
            }
        }

        // Flatten to [1, 80, T] tensor layout: m * numFrames + t
        val flatOutput = FloatArray(nMels * numFrames)
        for (m in 0 until nMels) {
            val offset = m * numFrames
            System.arraycopy(melSpectrogram[m], 0, flatOutput, offset, numFrames)
        }

        return PreprocessResult(
            features = flatOutput,
            numFrames = numFrames,
            nMels = nMels,
            isSilence = isSilence
        )
    }
}
