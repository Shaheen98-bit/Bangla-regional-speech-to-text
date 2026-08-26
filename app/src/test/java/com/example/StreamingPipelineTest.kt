package com.example

import com.example.engine.AudioFileProcessor
import com.example.engine.TranscriptAccumulator
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

    private lateinit var accumulator: TranscriptAccumulator

    @Before
    fun setup() {
        accumulator = TranscriptAccumulator()
    }

    /**
     * Test A: partial → partial → final
     * Expected: শুধু final text permanent হয়।
     */
    @Test
    fun testA_partialToPartialToFinal_onlyFinalIsPermanent() {
        // Step 1: partial update 1
        accumulator.updateLive("আমি আজ")
        assertEquals("আমি আজ", accumulator.liveTranscript)
        assertEquals("", accumulator.finalTranscript)

        // Step 2: partial update 2
        accumulator.updateLive("আমি আজ ঢাকায়")
        assertEquals("আমি আজ ঢাকায়", accumulator.liveTranscript)
        assertEquals("", accumulator.finalTranscript)

        // Step 3: final commit
        val commit = accumulator.commitFinal("আমি আজ ঢাকায় গিয়েছিলাম")
        assertNotNull(commit)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.finalTranscript)
        assertEquals("", accumulator.liveTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)
    }

    /**
     * Test B: final → silence → next partial
     * Expected: প্রথম final text অক্ষত থাকে।
     */
    @Test
    fun testB_finalThenSilenceThenNextPartial_firstFinalRemains() {
        // Step 1: Commit first final text
        accumulator.commitFinal("আমি আজ ঢাকায় গিয়েছিলাম")
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.finalTranscript)

        // Step 2: Silence event (never clears finalTranscript)
        accumulator.updateLive("")
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.finalTranscript)
        assertEquals("", accumulator.liveTranscript)

        // Step 3: Next in-progress partial
        accumulator.updateLive("তারপর বাজারে")
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম", accumulator.finalTranscript)
        assertEquals("তারপর বাজারে", accumulator.liveTranscript)
        assertEquals("আমি আজ ঢাকায় গিয়েছিলাম\nতারপর বাজারে", accumulator.displayedTranscript)
    }

    /**
     * Test C: ৫+ utterance
     * Expected: সব final sentence ধারাবাহিকভাবে থাকে।
     */
    @Test
    fun testC_fivePlusUtterances_allFinalSentencesRemain() {
        val sentences = listOf(
            "আমি আজ ঢাকায় গিয়েছিলাম",
            "তারপর বাজারে গেলাম",
            "কিছু ফলমূল কিনেছি",
            "রিকশায় চড়ে বাড়ি ফিরেছি",
            "বাড়িতে ফিরে চা খেলাম",
            "বই পড়তে বসলাম"
        )

        for ((idx, s) in sentences.withIndex()) {
            accumulator.updateLive(s.take(5))
            val seg = accumulator.commitFinal(s, utteranceId = (idx + 1).toLong())
            assertNotNull(seg)
        }

        assertEquals(6, accumulator.finalizedSegments.size)
        assertEquals(sentences.joinToString("\n"), accumulator.finalTranscript)
        assertEquals("", accumulator.liveTranscript)
    }

    /**
     * Test D: একই final callback দুইবার
     * Expected: একবারই transcript-এ থাকে (duplicate rejected)।
     */
    @Test
    fun testD_sameFinalCallbackTwice_onlyCommittedOnce() {
        val commit1 = accumulator.commitFinal("বারিত্তে সব তাহা পয়সা নিয়ায়গা", utteranceId = 101L)
        assertNotNull(commit1)
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", accumulator.finalTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)

        // Second duplicate commit with same text & same utterance ID
        val commit2 = accumulator.commitFinal("বারিত্তে সব তাহা পয়সা নিয়ায়গা", utteranceId = 101L)
        assertNull(commit2)
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", accumulator.finalTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)

        // Third duplicate commit without utterance ID
        val commit3 = accumulator.commitFinal("বারিত্তে সব তাহা পয়সা নিয়ায়গা", utteranceId = 0L)
        assertNull(commit3)
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", accumulator.finalTranscript)
        assertEquals(1, accumulator.finalizedSegments.size)
    }

    /**
     * Test E: recording stop with live partial
     * Expected: শেষ partial যতটা সম্ভব final হয়ে থাকে।
     */
    @Test
    fun testE_recordingStopWithLivePartial_commitsOnce() {
        accumulator.commitFinal("প্রথম বাক্য সম্পন্ন")
        assertEquals("প্রথম বাক্য সম্পন্ন", accumulator.finalTranscript)

        // User speaks and stops mid-sentence
        accumulator.updateLive("কাজলবুরর বাংলা ভয়েস টাইপিং")
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", accumulator.liveTranscript)

        val flushed = accumulator.flushOnStop()
        assertNotNull(flushed)
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", flushed?.text)
        assertEquals("", accumulator.liveTranscript)

        val expected = "প্রথম বাক্য সম্পন্ন\nকাজলবুরর বাংলা ভয়েস টাইপিং"
        assertEquals(expected, accumulator.finalTranscript)

        // Subsequent flush is no-op
        val secondFlush = accumulator.flushOnStop()
        assertNull(secondFlush)
        assertEquals(expected, accumulator.finalTranscript)
    }

    /**
     * Test F: Compose recomposition
     * Expected: finalTranscript unchanged।
     */
    @Test
    fun testF_composeRecomposition_finalTranscriptUnchanged() {
        val segments = listOf(
            TranscriptSegment(1L, "আমি আজ ঢাকায় গিয়েছিলাম"),
            TranscriptSegment(2L, "তারপর বাজারে গেলাম")
        )

        var uiState = SttUiState(
            finalizedSegments = segments,
            finalTranscript = segments.joinToString("\n") { it.text },
            liveTranscript = "এখন বিশ্রাম নিচ্ছি",
            isRecording = true
        )

        val expected = "আমি আজ ঢাকায় গিয়েছিলাম\nতারপর বাজারে গেলাম"
        assertEquals(expected, uiState.finalTranscript)
        assertEquals("এখন বিশ্রাম নিচ্ছি", uiState.liveTranscript)

        // Recomposition simulation: RMS update, tab change, config collapse
        uiState = uiState.copy(rmsLevel = 0.95f)
        assertEquals(expected, uiState.finalTranscript)

        uiState = uiState.copy(isConfigCollapsed = false)
        assertEquals(expected, uiState.finalTranscript)

        uiState = uiState.copy(selectedTab = 1)
        assertEquals(expected, uiState.finalTranscript)
    }

    /**
     * Test G: Audio File chunk 1/2/3
     * Expected: intermediate partial duplicate হয়ে জমে না।
     */
    @Test
    fun testG_audioFileChunks_intermediatePartialsNotDuplicated() {
        val fileAccumulator = TranscriptAccumulator()

        // Chunk 1: intermediate partial
        fileAccumulator.updateLive("এটে থাহা")
        assertEquals("এটে থাহা", fileAccumulator.liveTranscript)
        assertEquals("", fileAccumulator.finalTranscript)

        // Chunk 2: updated partial
        fileAccumulator.updateLive("এটে থাহা পয়সা")
        assertEquals("এটে থাহা পয়সা", fileAccumulator.liveTranscript)
        assertEquals("", fileAccumulator.finalTranscript)

        // Chunk 3: updated partial
        fileAccumulator.updateLive("এটটেটাহা পাইসা")
        assertEquals("এটটেটাহা পাইসা", fileAccumulator.liveTranscript)
        assertEquals("", fileAccumulator.finalTranscript)

        // Final utterance recognized: commits once
        val seg = fileAccumulator.commitFinal("এটে টাকা পয়সা", utteranceId = 1L)
        assertNotNull(seg)
        assertEquals("এটে টাকা পয়সা", fileAccumulator.finalTranscript)
        assertEquals("", fileAccumulator.liveTranscript)
        assertEquals(1, fileAccumulator.finalizedSegments.size)
    }

    /**
     * Test H: Audio File এবং Live একই TranscriptAccumulator ব্যবহার করছে।
     */
    @Test
    fun testH_audioFileAndLiveUseSameTranscriptAccumulatorClass() {
        val sharedAccumulator = TranscriptAccumulator()

        // 1. Used by Live
        sharedAccumulator.updateLive("লাইভ কথা")
        sharedAccumulator.commitFinal("লাইভ কথা সম্পন্ন")
        assertEquals("লাইভ কথা সম্পন্ন", sharedAccumulator.finalTranscript)

        // 2. Used by Audio File
        sharedAccumulator.updateLive("ফাইল অডিও")
        sharedAccumulator.commitFinal("ফাইল অডিও সম্পন্ন")
        assertEquals("লাইভ কথা সম্পন্ন\nফাইল অডিও সম্পন্ন", sharedAccumulator.finalTranscript)
        assertEquals(2, sharedAccumulator.finalizedSegments.size)
    }

    /**
     * Test I: user Clear
     * Expected: তখনই transcript empty হয়।
     */
    @Test
    fun testI_userClear_clearsTranscriptCompletely() {
        accumulator.commitFinal("প্রথম বাক্য")
        accumulator.commitFinal("দ্বিতীয় বাক্য")
        accumulator.updateLive("তৃতীয় বাক্য আংশিক")

        assertEquals("প্রথম বাক্য\nদ্বিতীয় বাক্য", accumulator.finalTranscript)
        assertEquals("তৃতীয় বাক্য আংশিক", accumulator.liveTranscript)

        // User clicks Clear
        accumulator.clear()

        assertEquals("", accumulator.finalTranscript)
        assertEquals("", accumulator.liveTranscript)
        assertEquals("", accumulator.displayedTranscript)
        assertEquals(0, accumulator.finalizedSegments.size)
    }

    /**
     * Test: Overlapping audio chunks deduction
     */
    @Test
    fun testOverlappingSuffixPrefixRemoval() {
        val chunk1 = "আমি আজ ঢাকায় গিয়েছিলাম"
        val chunk2 = "ঢাকায় গিয়েছিলাম তারপর বাজারে গেলাম"

        accumulator.commitFinal(chunk1)
        val seg2 = accumulator.commitFinal(chunk2)
        assertNotNull(seg2)
        assertEquals("তারপর বাজারে গেলাম", seg2?.text)

        val expected = "আমি আজ ঢাকায় গিয়েছিলাম\nতারপর বাজারে গেলাম"
        assertEquals(expected, accumulator.finalTranscript)
    }
}
