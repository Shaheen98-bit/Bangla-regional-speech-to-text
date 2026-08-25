package com.example

import com.example.engine.StreamingTranscriptAccumulator
import com.example.ui.SttUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamingPipelineTest {

    private lateinit var accumulator: StreamingTranscriptAccumulator

    @Before
    fun setup() {
        accumulator = StreamingTranscriptAccumulator()
    }

    /**
     * 1. Repeated overlapping CTC chunks:
     * Intermediate CTC chunks with slight spelling variations or repeats
     * must update currentPartial without polluting or duplicating fullTranscript.
     */
    @Test
    fun testRepeatedOverlappingCtcChunks() {
        // Progressive streaming chunks for the same utterance
        accumulator.updatePartial("এটে থাহা")
        accumulator.updatePartial("এটে থাহা পয়সা")
        accumulator.updatePartial("এটটেটাহা পাইসা")
        accumulator.updatePartial("এটে টাকা পয়সা")

        // fullTranscript must remain empty during active utterance
        assertEquals("", accumulator.fullTranscript)
        assertEquals("এটে টাকা পয়সা", accumulator.currentPartial)

        // On utterance boundary, finalize once
        val committed = accumulator.finalizeUtterance("এটে টাকা পয়সা")
        assertEquals("এটে টাকা পয়সা", committed)
        assertEquals("এটে টাকা পয়সা", accumulator.fullTranscript)
        assertEquals("", accumulator.currentPartial)
        assertEquals(1, accumulator.utteranceCount)

        // Duplicate finalize call with identical text must not create duplicate entry
        val duplicateCommit = accumulator.finalizeUtterance("এটে টাকা পয়সা")
        assertEquals("", duplicateCommit)
        assertEquals("এটে টাকা পয়সা", accumulator.fullTranscript)
        assertEquals(1, accumulator.utteranceCount)
    }

    /**
     * 2. Partial -> Final transition:
     * Streaming updates evolve currentPartial while fullTranscript is immutable.
     * On finalization, partial becomes finalized and cleared.
     */
    @Test
    fun testPartialToFinalTransition() {
        // Step 1: Speech starts
        accumulator.updatePartial("আমি")
        assertEquals("আমি", accumulator.currentPartial)
        assertEquals("", accumulator.fullTranscript)

        // Step 2: Speech continues
        accumulator.updatePartial("আমি বাংলায়")
        assertEquals("আমি বাংলায়", accumulator.currentPartial)
        assertEquals("", accumulator.fullTranscript)

        // Step 3: Speech finishes
        accumulator.updatePartial("আমি বাংলায় গান গাই")
        assertEquals("আমি বাংলায় গান গাই", accumulator.currentPartial)
        assertEquals("", accumulator.fullTranscript)

        // Step 4: Utterance boundary / silence detected -> Finalize
        val committed = accumulator.finalizeUtterance("আমি বাংলায় গান গাই")
        assertEquals("আমি বাংলায় গান গাই", committed)
        assertEquals("আমি বাংলায় গান গাই", accumulator.fullTranscript)
        assertEquals("", accumulator.currentPartial)
    }

    /**
     * 3. Five consecutive utterances:
     * Full conversation with 5 separate sentences appended safely in sequence.
     */
    @Test
    fun testFiveConsecutiveUtterances() {
        val sentences = listOf(
            "আমার সোনার বাংলা",
            "আমি তোমায় ভালোবাসি",
            "চিরদিন তোমার আকাশ",
            "তোমার বাতাস আমার প্রাণে",
            "বাজায় বাঁশি"
        )

        for (sentence in sentences) {
            // Streaming partials
            val words = sentence.split(" ")
            var partial = ""
            for (word in words) {
                partial = if (partial.isEmpty()) word else "$partial $word"
                accumulator.updatePartial(partial)
            }
            // Utterance boundary
            val committed = accumulator.finalizeUtterance(sentence)
            assertEquals(sentence, committed)
        }

        assertEquals(5, accumulator.utteranceCount)
        val expectedFull = sentences.joinToString("\n")
        assertEquals(expectedFull, accumulator.fullTranscript)
        assertEquals("", accumulator.currentPartial)
    }

    /**
     * 4. Silence between utterances:
     * Silence chunks arriving between sentences must never clear or corrupt fullTranscript.
     */
    @Test
    fun testSilenceBetweenUtterances() {
        // Utterance 1
        accumulator.updatePartial("প্রথম বাক্য")
        accumulator.finalizeUtterance("প্রথম বাক্য")
        assertEquals("প্রথম বাক্য", accumulator.fullTranscript)

        // Silence / VAD silence arriving
        // Empty partials or pure whitespace should not alter fullTranscript
        accumulator.updatePartial("")
        accumulator.finalizeUtterance("")
        assertEquals("প্রথম বাক্য", accumulator.fullTranscript)

        // Utterance 2
        accumulator.updatePartial("দ্বিতীয় বাক্য")
        accumulator.finalizeUtterance("দ্বিতীয় বাক্য")
        val expected = "প্রথম বাক্য\nদ্বিতীয় বাক্য"
        assertEquals(expected, accumulator.fullTranscript)
        assertEquals(2, accumulator.utteranceCount)
    }

    /**
     * 5. Stopping while partial text exists:
     * When user stops recording with in-flight unfinalized text,
     * flushOnStop() commits it safely exactly once and clears partial.
     */
    @Test
    fun testStoppingWhilePartialTextExists() {
        // Utterance 1 already finalized
        accumulator.finalizeUtterance("প্রথম বাক্য সম্পন্ন")
        assertEquals("প্রথম বাক্য সম্পন্ন", accumulator.fullTranscript)

        // User speaks utterance 2 and suddenly clicks STOP
        accumulator.updatePartial("কাজলবুরর বাংলা ভয়েস টাইপিং")
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", accumulator.currentPartial)

        // Stop triggered -> flushOnStop()
        val flushed = accumulator.flushOnStop()
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", flushed)
        assertEquals("", accumulator.currentPartial)

        val expected = "প্রথম বাক্য সম্পন্ন\nকাজলবুরর বাংলা ভয়েস টাইপিং"
        assertEquals(expected, accumulator.fullTranscript)

        // Second stop or flush should do nothing
        val secondFlush = accumulator.flushOnStop()
        assertEquals("", secondFlush)
        assertEquals(expected, accumulator.fullTranscript)
    }

    /**
     * 6. Compose recomposition / State updates:
     * Verifies SttUiState immutable separation: fullTranscript and currentPartial
     * are independent fields, and recomposition copies do not wipe fullTranscript.
     */
    @Test
    fun testComposeRecompositionStateUpdates() {
        var state = SttUiState(
            fullTranscript = "স্থায়ী অনুচ্ছেদ",
            currentPartial = "নতুন চলমান কথা",
            liveTranscript = "নতুন চলমান কথা",
            isRecording = true
        )

        // Simulate Compose recomposition with new RMS amplitude or frame update
        state = state.copy(rmsLevel = 0.5f, audioFrameEnd = 16000L)
        assertEquals("স্থায়ী অনুচ্ছেদ", state.fullTranscript)
        assertEquals("নতুন চলমান কথা", state.currentPartial)

        // Simulate VAD silence update during active recording
        state = state.copy(vadState = "SILENCE", rmsLevel = 0.001f)
        assertEquals("স্থায়ী অনুচ্ছেদ", state.fullTranscript)

        // Simulate finalization
        state = state.copy(
            fullTranscript = "স্থায়ী অনুচ্ছেদ\nনতুন চলমান কথা",
            currentPartial = "",
            liveTranscript = "",
            vadState = "PAUSE_BOUNDARY"
        )
        assertEquals("স্থায়ী অনুচ্ছেদ\nনতুন চলমান কথা", state.fullTranscript)
        assertEquals("", state.currentPartial)

        // Explicit user clear action
        state = state.copy(fullTranscript = "", currentPartial = "", liveTranscript = "")
        assertEquals("", state.fullTranscript)
        assertEquals("", state.currentPartial)
    }

    /**
     * 7. Bengali Normalization & Overlap Removal:
     * Tests ZWJ/ZWNJ removal, whitespace normalization, and word-overlap stripping.
     */
    @Test
    fun testBengaliNormalizationAndOverlap() {
        // ZWJ and ZWNJ removal
        val textWithZwj = "বাং\u200Dলা\u200Cদেশ"
        val normalized = accumulator.normalizeBengali(textWithZwj)
        assertEquals("বাংলাদেশ", normalized)

        // Word overlap removal
        accumulator.finalizeUtterance("আমি বাংলায় গান গাই")
        // Candidate starts with suffix "গান গাই" and continues with "প্রতিদিন"
        val nonOverlapping = accumulator.removeOverlapWithHistory("গান গাই প্রতিদিন সকালে")
        assertEquals("প্রতিদিন সকালে", nonOverlapping)
    }
}
