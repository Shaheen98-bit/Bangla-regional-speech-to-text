package com.example

import com.example.dsp.FftRadix2
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.engine.CtcDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testFftRadix2() {
        val fft = FftRadix2(512)
        val real = FloatArray(512) { 1.0f }
        val imag = FloatArray(512)

        fft.transform(real, imag)
        // DC component should be sum of inputs = 512
        assertEquals(512.0f, real[0], 0.01f)
        assertEquals(0.0f, imag[0], 0.01f)
    }

    @Test
    fun testMelSpectrogramPreprocessor() {
        val preprocessor = MelSpectrogramPreprocessor(
            sampleRate = 16000,
            nMels = 80,
            nFft = 512
        )

        // 1 second of audio = 16000 samples
        val samples = ShortArray(16000) { (it % 1000).toShort() }
        val result = preprocessor.process(samples)

        assertEquals(80, result.nMels)
        assertTrue("Frames should be positive", result.numFrames > 0)
        assertEquals(80 * result.numFrames, result.features.size)
    }

    @Test
    fun testCtcDecoderGreedy() {
        val ctcDecoder = CtcDecoder(blankIndex = 128)
        val numClasses = 129
        val numFrames = 5

        // Synthetic logprobs where frames 0, 1 predict token 5, frame 2 predicts blank 128, frame 3, 4 predict token 10
        val logprobs = FloatArray(numFrames * numClasses) { -100f }
        logprobs[0 * numClasses + 5] = 10f
        logprobs[1 * numClasses + 5] = 10f
        logprobs[2 * numClasses + 128] = 10f
        logprobs[3 * numClasses + 10] = 10f
        logprobs[4 * numClasses + 10] = 10f

        val result = ctcDecoder.decode(logprobs, numFrames, numClasses, null)

        assertEquals(listOf(5, 10), result.tokenIds)
    }
}
