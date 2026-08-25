package com.example.dsp

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Generates an 80-channel Mel Filterbank matrix matching NeMo's AudioToMelSpectrogramPreprocessor.
 * NeMo uses standard Slaney/HTK triangular filterbank with n_mels=80, n_fft=512, sample_rate=16000.
 */
class MelFilterbank(
    val nMels: Int = 80,
    val nFft: Int = 512,
    val sampleRate: Int = 16000,
    val fMin: Float = 0.0f,
    val fMax: Float = 8000.0f
) {
    val numFreqBins: Int = nFft / 2 + 1 // 257 for 512-FFT
    // Flattened filterbank weights: shape [nMels, numFreqBins]
    val weights: Array<FloatArray> = Array(nMels) { FloatArray(numFreqBins) }

    init {
        buildFilterbank()
    }

    private fun hzToMel(hz: Float): Float {
        // Standard HTK / Slaney mel conversion: 2595 * log10(1 + hz / 700)
        return 2595.0f * log10(1.0f + hz / 700.0f)
    }

    private fun melToHz(mel: Float): Float {
        return 700.0f * (10.0f.pow(mel / 2595.0f) - 1.0f)
    }

    private fun buildFilterbank() {
        val minMel = hzToMel(fMin)
        val maxMel = hzToMel(fMax)

        // nMels + 2 linearly spaced points in Mel scale
        val melPoints = FloatArray(nMels + 2)
        val deltaMel = (maxMel - minMel) / (nMels + 1)
        for (i in 0 until nMels + 2) {
            melPoints[i] = minMel + i * deltaMel
        }

        // Convert back to Hz and then to FFT bin indices (fractional)
        val binPoints = FloatArray(nMels + 2)
        val hzPerBin = sampleRate.toFloat() / nFft.toFloat()
        for (i in 0 until nMels + 2) {
            val hz = melToHz(melPoints[i])
            binPoints[i] = hz / hzPerBin
        }

        // Construct triangular filters with Slaney normalization (area = 2 / (f_high - f_low))
        for (m in 0 until nMels) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            val leftBin = max(0, floor(left).toInt())
            val rightBin = min(numFreqBins - 1, kotlin.math.ceil(right).toInt())

            for (k in leftBin..rightBin) {
                val freqBin = k.toFloat()
                if (freqBin in left..center && center > left) {
                    weights[m][k] = (freqBin - left) / (center - left)
                } else if (freqBin in center..right && right > center) {
                    weights[m][k] = (right - freqBin) / (right - center)
                }
            }

            // Slaney norm factor: 2.0 / (f_right - f_left) in Hz
            val hzLeft = melToHz(melPoints[m])
            val hzRight = melToHz(melPoints[m + 2])
            val enorm = if (hzRight > hzLeft) 2.0f / (hzRight - hzLeft) else 1.0f

            for (k in 0 until numFreqBins) {
                weights[m][k] *= enorm
            }
        }
    }
}
