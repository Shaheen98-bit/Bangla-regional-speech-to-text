package com.example

import com.example.dsp.FftRadix2
import com.example.dsp.MelFilterbank
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.engine.CtcDecoder
import com.example.tokenizer.SentencePieceProtoParser
import com.example.tokenizer.SentencePieceTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
    fun testMelFilterbankSlaney() {
        val filterbank = MelFilterbank(
            nMels = 80,
            nFft = 512,
            sampleRate = 16000,
            fMin = 0f,
            fMax = 8000f
        )
        assertEquals(80, filterbank.weights.size)
        assertEquals(257, filterbank.weights[0].size)

        // Ensure all weights are non-negative and normalized
        var nonZeroFilterCount = 0
        for (m in 0 until 80) {
            val sum = filterbank.weights[m].sum()
            assertTrue("Filter $m sum should be non-negative", sum >= 0f)
            if (sum > 0f) nonZeroFilterCount++
        }
        assertTrue("Most mel filters should have positive weights", nonZeroFilterCount > 70)
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
        assertEquals(5, result.rawArgmax.size)
    }

    @Test
    fun testCtcBlankIndexUpdate() {
        val ctcDecoder = CtcDecoder()
        // 128 vocab, 129 classes -> blank index must be 128
        ctcDecoder.updateBlankIndexFromVocab(128, 129)
        assertEquals(128, ctcDecoder.blankIndex)

        // 100 vocab, 101 classes -> blank index must be 100
        ctcDecoder.updateBlankIndexFromVocab(100, 101)
        assertEquals(100, ctcDecoder.blankIndex)
    }

    @Test
    fun testSentencePieceDecodingBengaliSentences() {
        // Build mock SentencePiece pieces for Bengali test sentences
        val mockPieces = listOf(
            SentencePieceProtoParser.Piece("<unk>", 0f, 2), // 0
            SentencePieceProtoParser.Piece("<s>", 0f, 3),   // 1
            SentencePieceProtoParser.Piece("</s>", 0f, 3),  // 2
            SentencePieceProtoParser.Piece("\u2581বাংলাদেশ", -1.0f, 1), // 3
            SentencePieceProtoParser.Piece("ের", -1.5f, 1),             // 4
            SentencePieceProtoParser.Piece("\u2581জনসংখ্যা", -2.0f, 1),  // 5
            SentencePieceProtoParser.Piece("\u2581আমি", -1.0f, 1),       // 6
            SentencePieceProtoParser.Piece("\u2581ভাত", -1.2f, 1),       // 7
            SentencePieceProtoParser.Piece("\u2581খাই", -1.3f, 1),       // 8
            SentencePieceProtoParser.Piece("\u2581আজকে", -1.4f, 1),      // 9
            SentencePieceProtoParser.Piece("\u2581আবহাওয়া", -1.5f, 1),   // 10
            SentencePieceProtoParser.Piece("\u2581খুব", -1.6f, 1),       // 11
            SentencePieceProtoParser.Piece("\u2581ভালো", -1.7f, 1),      // 12
            SentencePieceProtoParser.Piece("\u2581বাংলা", -1.0f, 1),     // 13
            SentencePieceProtoParser.Piece("\u2581আমার", -1.1f, 1),      // 14
            SentencePieceProtoParser.Piece("\u2581মাতৃভাষা", -1.2f, 1)   // 15
        )

        val tokenizerConstructor = SentencePieceTokenizer::class.java.getDeclaredConstructor(List::class.java)
        tokenizerConstructor.isAccessible = true
        val tokenizer = tokenizerConstructor.newInstance(mockPieces) as SentencePieceTokenizer

        // Test 1: "বাংলাদেশের জনসংখ্যা"
        val tokens1 = listOf(3, 4, 5) // " বাংলাদেশ", "ের", " জনসংখ্যা"
        val decoded1 = tokenizer.decode(tokens1)
        assertEquals("বাংলাদেশের জনসংখ্যা", decoded1)

        // Test 2: "আমি ভাত খাই"
        val tokens2 = listOf(6, 7, 8)
        val decoded2 = tokenizer.decode(tokens2)
        assertEquals("আমি ভাত খাই", decoded2)

        // Test 3: "আজকে আবহাওয়া খুব ভালো"
        val tokens3 = listOf(9, 10, 11, 12)
        val decoded3 = tokenizer.decode(tokens3)
        assertEquals("আজকে আবহাওয়া খুব ভালো", decoded3)

        // Test 4: "বাংলা আমার মাতৃভাষা"
        val tokens4 = listOf(13, 14, 15)
        val decoded4 = tokenizer.decode(tokens4)
        assertEquals("বাংলা আমার মাতৃভাষা", decoded4)

        // Test CTC integration with sentence 1
        val ctcDecoder = CtcDecoder(blankIndex = 128)
        val numClasses = 129
        val numFrames = 7
        val logprobs = FloatArray(numFrames * numClasses) { -100f }
        logprobs[0 * numClasses + 3] = 10f   // বাংলাদেশ
        logprobs[1 * numClasses + 3] = 10f   // বাংলাদেশ (repeat)
        logprobs[2 * numClasses + 4] = 10f   // ের
        logprobs[3 * numClasses + 128] = 10f // blank
        logprobs[4 * numClasses + 5] = 10f   // জনসংখ্যা
        logprobs[5 * numClasses + 5] = 10f   // জনসংখ্যা (repeat)
        logprobs[6 * numClasses + 128] = 10f // blank

        val ctcResult = ctcDecoder.decode(logprobs, numFrames, numClasses, tokenizer)
        assertEquals(listOf(3, 4, 5), ctcResult.collapsedIds)
        assertEquals("বাংলাদেশের জনসংখ্যা", ctcResult.text)
    }
}
