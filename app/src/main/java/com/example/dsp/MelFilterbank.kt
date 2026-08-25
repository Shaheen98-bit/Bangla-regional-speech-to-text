package com.example.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Generates an 80-channel Mel Filterbank matrix matching NeMo's AudioToMelSpectrogramPreprocessor.
 * NeMo uses librosa.filters.mel(sr=16000, n_fft=512, n_mels=80, fmin=0.0, fmax=8000.0, htk=False, norm='slaney').
 */
class MelFilterbank(
    val nMels: Int = 80,
    val nFft: Int = 512,
    val sampleRate: Int = 16000,
    val fMin: Float = 0.0f,
    val fMax: Float = 8000.0f
) {
    val numFreqBins: Int = nFft / 2 + 1 // 257 for 512-FFT
    // Filterbank weights: shape [nMels, numFreqBins]
    val weights: Array<FloatArray> = Array(nMels) { FloatArray(numFreqBins) }

    init {
        buildFilterbank()
    }

    /**
     * Slaney mel scale conversion (htk=False in librosa / NeMo).
     * Linear below 1000 Hz, logarithmic above 1000 Hz.
     */
    private fun hzToMel(hz: Float): Float {
        val fSp = 200.0f / 3.0f // 66.6667 Hz per mel in linear region
        val minLogHz = 1000.0f
        val minLogMel = (minLogHz - fMin) / fSp // 15.0 mels
        val logStep = ln(6.4f) / 27.0f          // ~0.06875

        return if (hz < minLogHz) {
            (hz - fMin) / fSp
        } else {
            minLogMel + ln(hz / minLogHz) / logStep
        }
    }

    /**
     * Slaney mel to Hz inverse conversion.
     */
    private fun melToHz(mel: Float): Float {
        val fSp = 200.0f / 3.0f
        val minLogHz = 1000.0f
        val minLogMel = (minLogHz - fMin) / fSp // 15.0 mels
        val logStep = ln(6.4f) / 27.0f

        return if (mel < minLogMel) {
            fMin + fSp * mel
        } else {
            minLogHz * exp(logStep * (mel - minLogMel))
        }
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
        val hzPerBin = sampleRate.toFloat() / nFft.toFloat() // 31.25 Hz per bin for 16k/512
        for (i in 0 until nMels + 2) {
            val hz = melToHz(melPoints[i])
            binPoints[i] = hz / hzPerBin
        }

        // Construct triangular filters with Slaney normalization (area = 2 / (f_high - f_low))
        for (m in 0 until nMels) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            val leftBin = max(0, kotlin.math.floor(left).toInt())
            val rightBin = min(numFreqBins - 1, kotlin.math.ceil(right).toInt())

            for (k in leftBin..rightBin) {
                val freqBin = k.toFloat()
                if (freqBin >= left && freqBin <= center && center > left) {
                    weights[m][k] = (freqBin - left) / (center - left)
                } else if (freqBin > center && freqBin <= right && right > center) {
                    weights[m][k] = (right - freqBin) / (right - center)
                }
            }

            // Slaney normalization: 2.0 / (f_right - f_left) in Hz
            val hzLeft = melToHz(melPoints[m])
            val hzRight = melToHz(melPoints[m + 2])
            val enorm = if (hzRight > hzLeft) 2.0f / (hzRight - hzLeft) else 1.0f

            for (k in 0 until numFreqBins) {
                weights[m][k] *= enorm
            }
        }
    }
}
