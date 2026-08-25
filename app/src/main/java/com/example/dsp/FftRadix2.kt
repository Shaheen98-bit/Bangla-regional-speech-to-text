package com.example.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance Radix-2 Cooley-Tukey FFT for power-of-two sizes (e.g. 512).
 * Precalculates twiddle factors and bit-reversal indices to avoid runtime allocations.
 */
class FftRadix2(val n: Int) {
    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2, got $n" }
    }

    private val bitReverse = IntArray(n)
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        val levels = 31 - Integer.numberOfLeadingZeros(n)
        for (i in 0 until n) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - levels)
        }
        for (i in 0 until n / 2) {
            val angle = -2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    /**
     * In-place forward FFT.
     * @param real Real components (size n), modified in-place
     * @param imag Imaginary components (size n), modified in-place
     */
    fun transform(real: FloatArray, imag: FloatArray) {
        // Bit-reversal permutation
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
        }

        // Cooley-Tukey radix-2 FFT
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val step = n / size
            var k = 0
            while (k < n) {
                var j = 0
                while (j < halfSize) {
                    val tableIndex = j * step
                    val cosVal = cosTable[tableIndex]
                    val sinVal = sinTable[tableIndex]

                    val tr = real[k + j + halfSize] * cosVal - imag[k + j + halfSize] * sinVal
                    val ti = real[k + j + halfSize] * sinVal + imag[k + j + halfSize] * cosVal

                    real[k + j + halfSize] = real[k + j] - tr
                    imag[k + j + halfSize] = imag[k + j] - ti
                    real[k + j] += tr
                    imag[k + j] += ti
                    j++
                }
                k += size
            }
            size *= 2
        }
    }
}
