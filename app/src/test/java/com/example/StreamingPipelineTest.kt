package com.example

import com.example.engine.AudioFileProcessor
import com.example.engine.StreamingTranscriptAccumulator
import com.example.engine.TranscriptSegment
import com.example.ui.SttUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
     * 1. Partial result does not erase finalized text
     */
    @Test
    fun testPartialResultDoesNotEraseFinalizedText() {
        // Step 1: Finalize first sentence
        val firstCommit = accumulator.commitUtterance("আমি আজ ঢাকায় গিয়েছিলাম")
        assertNotNull(firstCommit)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)

        // Step 2: Interim partial arrives for next sentence
        accumulator.updateInterim("তারপর বাজারে")
        assertEquals("তারপর বাজারে", accumulator.currentUtterance)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.fullTranscript) // MUST NOT BE ERASED

        accumulator.updateInterim("তারপর বাজারে গেলাম")
        assertEquals("তারপর বাজারে গেলাম", accumulator.currentUtterance)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.fullTranscript) // STILL INTACT
    }

    /**
     * 2. 10 consecutive finalized utterances remain visible in exact sequence
     */
    @Test
    fun testTenConsecutiveFinalizedUtterances() {
        val utterances = listOf(
            "আমি আজ ঢাকায় গিয়েছিলাম",
            "তারপর বাজারে গেলাম",
            "কিছু ফলমূল কিনেছি",
            "রিকশায় চড়ে বাড়ি ফিরেছি",
            "বাড়িতে ফিরে চা খেলাম",
            "বই পড়তে বসলাম",
            "কিছুক্ষণ বিশ্রাম নিলাম",
            "সন্ধ্যায় বন্ধুদের সাথে দেখা হলো",
            "রাতের খাবার খেলাম",
            "এবার ঘুমাতে যাচ্ছি"
        )

        for ((index, u) in utterances.withIndex()) {
            // Streaming partials leading up to finalization
            accumulator.updateInterim(u.substring(0, u.length / 2))
            val seg = accumulator.commitUtterance(u, utteranceId = (index + 1).toLong())
            assertNotNull(seg)
        }

        assertEquals(10, accumulator.finalizedSegments.size)
        assertEquals(10, accumulator.utteranceCount)

        val expectedFull = utterances.joinToString("\n")
        assertEquals(expectedFull, accumulator.fullTranscript)
        assertEquals("", accumulator.currentUtterance)
    }

    /**
     * 3. Stop commits the final partial exactly once
     */
    @Test
    fun testStopCommitsFinalPartialExactlyOnce() {
        accumulator.commitUtterance("প্রথম বাক্য সম্পন্ন")
        assertEquals("প্রথম বাক্য সম্পন্ন", accumulator.fullTranscript)

        // User speaks and stops mid-sentence
        accumulator.updateInterim("কাজলবুরর বাংলা ভয়েস টাইপিং")
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", accumulator.currentUtterance)

        val flushed = accumulator.flushOnStop()
        assertNotNull(flushed)
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", flushed?.text)
        assertEquals("", accumulator.currentUtterance)

        val expected = "প্রথম বাক্য সম্পন্ন\nকাজলবুরর বাংলা ভয়েস টাইপিং"
        assertEquals(expected, accumulator.fullTranscript)

        // Calling flushOnStop again must be a no-op
        val secondFlush = accumulator.flushOnStop()
        assertNull(secondFlush)
        assertEquals(expected, accumulator.fullTranscript)
    }

    /**
     * 4. Repeated VAD callbacks do not duplicate a sentence
     */
    @Test
    fun testRepeatedVadCallbacksDoNotDuplicateSentence() {
        accumulator.updateInterim("আমি তোমায় ভালোবাসি")

        // First VAD pause callback
        val commit1 = accumulator.commitUtterance("আমি তোমায় ভালোবাসি", utteranceId = 42L)
        assertNotNull(commit1)
        assertEquals("আমি তোমায় ভালোবাসি", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)

        // Second VAD callback with same utteranceId or same text
        val commit2 = accumulator.commitUtterance("আমি তোমায় ভালোবাসি", utteranceId = 42L)
        assertNull(commit2)
        assertEquals("আমি তোমায় ভালোবাসি", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)

        // Third VAD callback with empty string
        val commit3 = accumulator.commitUtterance("", utteranceId = 42L)
        assertNull(commit3)
        assertEquals("আমি তোমায় ভালোবাসি", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)
    }

    /**
     * 5. Identical decoder result cannot be committed twice
     */
    @Test
    fun testIdenticalDecoderResultCannotBeCommittedTwice() {
        val seg1 = accumulator.commitUtterance("চিরদিন তোমার আকাশ")
        assertNotNull(seg1)
        assertEquals("চিরদিন তোমার আকাশ", accumulator.fullTranscript)

        // Attempt duplicate commit
        val seg2 = accumulator.commitUtterance("চিরদিন তোমার আকাশ")
        assertNull(seg2)
        assertEquals("চিরদিন তোমার আকাশ", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)
    }

    /**
     * 6. Overlapping audio chunks do not duplicate words (Suffix/Prefix overlap removal)
     */
    @Test
    fun testOverlappingAudioChunksDoNotDuplicateWords() {
        val chunk1 = "আমি আজ ঢাকায় গিয়েছিলাম"
        val chunk2 = "ঢাকায় গিয়েছিলাম তারপর বাজারে গেলাম"

        val seg1 = accumulator.commitUtterance(chunk1)
        assertNotNull(seg1)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.fullTranscript)

        // Suffix/prefix deduction
        val nonOverlapping = accumulator.removeSuffixPrefixOverlap(chunk1, chunk2)
        assertEquals("তারপর বাজারে গেলাম", nonOverlapping)

        val seg2 = accumulator.commitUtterance(chunk2)
        assertNotNull(seg2)
        assertEquals("তারপর বাজারে গেলাম", seg2?.text)

        val expected = "আমি আজ ঢাকায় গিয়েছিলাম\nতারপর বাজারে গেলাম"
        assertEquals(expected, accumulator.fullTranscript)
    }

    /**
     * 7. Audio file segmentation & boundary deduplication test
     */
    @Test
    fun testAudioFileSegmentationDeduplication() {
        val text1 = "আমার সোনার বাংলা আমি তোমায় ভালোবাসি"
        val text2 = "আমি তোমায় ভালোবাসি চিরদিন তোমার আকাশ"

        val seg1 = accumulator.commitUtterance(text1)
        assertNotNull(seg1)

        val seg2 = accumulator.commitUtterance(text2)
        assertNotNull(seg2)
        assertEquals("চিরদিন তোমার আকাশ", seg2?.text)

        val expected = "আমার সোনার বাংলা আমি তোমায় ভালোবাসি\nচিরদিন তোমার আকাশ"
        assertEquals(expected, accumulator.fullTranscript)
    }

    /**
     * 8. Compose recomposition does not clear or alter transcript
     */
    @Test
    fun testComposeRecompositionDoesNotClearTranscript() {
        val segmentList = listOf(
            TranscriptSegment(1L, "আমি আজ ঢাকায় গিয়েছিলাম"),
            TranscriptSegment(2L, "তারপর বাজারে গেলাম"),
            TranscriptSegment(3L, "বাড়িতে ফিরে এসেছি")
        )

        var uiState = SttUiState(
            finalizedSegments = segmentList,
            fullTranscript = segmentList.joinToString("\n") { it.text },
            currentUtterance = "এখন বিশ্রাম নিচ্ছি",
            interimText = "এখন বিশ্রাম নিচ্ছি",
            liveTranscript = "এখন বিশ্রাম নিচ্ছি",
            isRecording = true
        )

        // Verify initial state
        val expectedInitial = "আমি আজ ঢাকায় গিয়েছিলাম\nতারপর বাজারে গেলাম\nবাড়িতে ফিরে এসেছি"
        assertEquals(expectedInitial, uiState.fullTranscript)
        assertEquals("এখন বিশ্রাম নিচ্ছি", uiState.currentUtterance)
        assertEquals(3, uiState.finalizedSegments.size)

        // Simulate multiple Compose UI recompositions (e.g. scroll, amplitude change, vad change)
        uiState = uiState.copy(rmsLevel = 0.85f, audioFrameEnd = 32000L)
        assertEquals(expectedInitial, uiState.fullTranscript)
        assertEquals(3, uiState.finalizedSegments.size)

        uiState = uiState.copy(vadState = "SILENCE", rmsLevel = 0.001f)
        assertEquals(expectedInitial, uiState.fullTranscript)
        assertEquals(3, uiState.finalizedSegments.size)

        // Verify it NEVER becomes a duplicate repetition
        val lines = uiState.fullTranscript.split("\n")
        assertEquals(3, lines.size)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", lines[0])
        assertEquals("তারপর বাজারে গেলাম", lines[1])
        assertEquals("বাড়িতে ফিরে এসেছি", lines[2])
    }

    /**
     * 9. Starting a new utterance does not erase previous utterances
     */
    @Test
    fun testStartingNewUtteranceDoesNotErasePrevious() {
        accumulator.commitUtterance("প্রথম বাক্য")
        assertEquals("প্রথম বাক্য", accumulator.fullTranscript)

        // Start utterance 2
        accumulator.updateInterim("দ্বি")
        assertEquals("প্রথম বাক্য", accumulator.fullTranscript)
        assertEquals("দ্বি", accumulator.currentUtterance)

        accumulator.updateInterim("দ্বিতীয় বাক্য শুরু")
        assertEquals("প্রথম বাক্য", accumulator.fullTranscript)

        accumulator.commitUtterance("দ্বিতীয় বাক্য সম্পন্ন")
        val expected = "প্রথম বাক্য\nদ্বিতীয় বাক্য সম্পন্ন"
        assertEquals(expected, accumulator.fullTranscript)
        assertEquals(2, accumulator.finalizedSegments.size)
    }

    /**
     * 10. Empty or whitespace decoder results are ignored
     */
    @Test
    fun testEmptyOrWhitespaceDecoderResultsIgnored() {
        accumulator.commitUtterance("বৈধ বাক্য")
        assertEquals("বৈধ বাক্য", accumulator.fullTranscript)

        val emptySeg = accumulator.commitUtterance("   \n\t  ")
        assertNull(emptySeg)
        assertEquals("বৈধ বাক্য", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)
    }

    /**
     * 11. Reported Bangla failure with interim spelling variations:
     * Intermediate variations ("এটে থাহা", "এটে থাহা পয়সা", "এটটেটাহা পাইসা")
     * must only update currentUtterance, and finalize ONCE as "এটে টাকা পয়সা".
     */
    @Test
    fun testBanglaInterimSpellingVariationsDoNotDuplicate() {
        // Stream of noisy interim hypotheses
        accumulator.updateInterim("এটে থাহা")
        assertEquals("এটে থাহা", accumulator.currentUtterance)
        assertEquals("", accumulator.fullTranscript)

        accumulator.updateInterim("এটে থাহা পয়সা")
        assertEquals("এটে থাহা পয়সা", accumulator.currentUtterance)
        assertEquals("", accumulator.fullTranscript)

        accumulator.updateInterim("এটটেটাহা পাইসা")
        assertEquals("এটটেটাহা পাইসা", accumulator.currentUtterance)
        assertEquals("", accumulator.fullTranscript)

        accumulator.updateInterim("এটে টাকা পয়সা")
        assertEquals("এটে টাকা পয়সা", accumulator.currentUtterance)
        assertEquals("", accumulator.fullTranscript)

        // Utterance boundary
        val committed = accumulator.commitUtterance("এটে টাকা পয়সা", utteranceId = 1L)
        assertNotNull(committed)
        assertEquals("এটে টাকা পয়সা", committed?.text)
        assertEquals("এটে টাকা পয়সা", accumulator.fullTranscript)
        assertEquals("", accumulator.currentUtterance)
        assertEquals(1, accumulator.finalizedSegments.size)

        // Duplicate final commit attempt
        val dup = accumulator.commitUtterance("এটে টাকা পয়সা", utteranceId = 1L)
        assertNull(dup)
        assertEquals("এটে টাকা পয়সা", accumulator.fullTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)
    }
}
