package com.example

import com.example.engine.LiveHypothesisGroup
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
     * Test 1: Single Chunk Hypothesis History Retention
     * partial A -> partial B -> partial C -> final C
     * Expected: All 3 hypotheses (A, B, C) are retained in group history, final C is currentBest & finalized.
     */
    @Test
    fun test1_singleChunk_allHypothesesRetainedInHistory() {
        // Step 1: partial A
        accumulator.updateLive("বারিত্তে সব তাহা")
        // Step 2: partial B
        accumulator.updateLive("বাড়িতে সব টাকা পয়সা")
        // Step 3: partial C
        accumulator.updateLive("বারিত্তে সব তাহা পয়সা নিয়ায়গা")

        assertEquals(1, accumulator.liveHypothesisHistory.size)
        val activeGroup = accumulator.liveHypothesisHistory[0]
        assertEquals(3, activeGroup.hypotheses.size)
        assertEquals("বারিত্তে সব তাহা", activeGroup.hypotheses[0])
        assertEquals("বাড়িতে সব টাকা পয়সা", activeGroup.hypotheses[1])
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", activeGroup.hypotheses[2])
        assertEquals(false, activeGroup.isFinalized)

        // Step 4: Final commit
        val commit = accumulator.commitFinal("বারিত্তে সব তাহা পয়সা নিয়ায়গা")
        assertNotNull(commit)

        assertEquals(1, accumulator.liveHypothesisHistory.size)
        val finalizedGroup = accumulator.liveHypothesisHistory[0]
        assertEquals(3, finalizedGroup.hypotheses.size)
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", finalizedGroup.currentBest)
        assertEquals(true, finalizedGroup.isFinalized)
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", accumulator.finalTranscript)
    }

    /**
     * Test 2: Exact Duplicate Callbacks Collapse
     * A, A, A, B, B, C -> Expected: A, B, C in group history.
     */
    @Test
    fun test2_duplicateHypothesisCallbacks_collapseDuplicates() {
        // 3 consecutive identical callbacks of A
        accumulator.updateLive("বারিত্তে সব তাহা")
        accumulator.updateLive("বারিত্তে সব তাহা")
        accumulator.updateLive("বারিত্তে সব তাহা")

        // 2 consecutive identical callbacks of B
        accumulator.updateLive("বাড়িতে সব টাকা পয়সা")
        accumulator.updateLive("বাড়িতে সব টাকা পয়সা")

        // Callback C
        accumulator.updateLive("বারিত্তে সব তাহা পয়সা নিয়ায়গা")

        assertEquals(1, accumulator.liveHypothesisHistory.size)
        val group = accumulator.liveHypothesisHistory[0]
        assertEquals(3, group.hypotheses.size)
        assertEquals("বারিত্তে সব তাহা", group.hypotheses[0])
        assertEquals("বাড়িতে সব টাকা পয়সা", group.hypotheses[1])
        assertEquals("বারিত্তে সব তাহা পয়সা নিয়ায়গা", group.hypotheses[2])
    }

    /**
     * Test 3: Multiple Distinct Chunks Retain Separate Histories
     * Chunk 1 (A, B, C) and Chunk 2 (D, E)
     * Expected: 2 distinct groups, all hypotheses preserved across both chunks.
     */
    @Test
    fun test3_multipleChunks_allHypothesisGroupsRetainedSeparately() {
        // Chunk 1
        accumulator.updateLive("বারিত্তে সব তাহা")
        accumulator.updateLive("বাড়িতে সব টাকা পয়সা")
        accumulator.updateLive("বারিত্তে সব তাহা পয়সা নিয়ায়গা")
        accumulator.commitFinal("বারিত্তে সব তাহা পয়সা নিয়ায়গা", utteranceId = 1L)

        // Chunk 2
        accumulator.updateLive("তারপর বাজারে")
        accumulator.updateLive("তারপর বাজারে ফলমূল কিনতে গেলাম")
        accumulator.commitFinal("তারপর বাজারে ফলমূল কিনতে গেলাম", utteranceId = 2L)

        assertEquals(2, accumulator.liveHypothesisHistory.size)
        assertEquals(3, accumulator.liveHypothesisHistory[0].hypotheses.size)
        assertEquals(2, accumulator.liveHypothesisHistory[1].hypotheses.size)
        assertEquals(true, accumulator.liveHypothesisHistory[0].isFinalized)
        assertEquals(true, accumulator.liveHypothesisHistory[1].isFinalized)

        val expectedFinal = "বারিত্তে সব তাহা পয়সা নিয়ায়গা\nতারপর বাজারে ফলমূল কিনতে গেলাম"
        assertEquals(expectedFinal, accumulator.finalTranscript)
    }

    /**
     * Test 4: Silence Handling
     * A -> B -> silence event -> Expected: A, B hypotheses remain untouched.
     */
    @Test
    fun test4_silenceEvent_preservesHypothesisHistory() {
        accumulator.updateLive("আমি আজ")
        accumulator.updateLive("আমি আজ ঢাকায়")

        // Silence / empty update
        accumulator.updateLive("")

        assertEquals(1, accumulator.liveHypothesisHistory.size)
        assertEquals(2, accumulator.liveHypothesisHistory[0].hypotheses.size)
        assertEquals("আমি আজ", accumulator.liveHypothesisHistory[0].hypotheses[0])
        assertEquals("আমি আজ ঢাকায়", accumulator.liveHypothesisHistory[0].hypotheses[1])
    }

    /**
     * Test 5: Recording Stop
     * A -> B -> stop -> Expected: A, B remain, finalized on stop.
     */
    @Test
    fun test5_recordingStop_finalizesActiveHypothesisGroupWithoutLosingHistory() {
        accumulator.updateLive("কাজলবুরর বাংলা")
        accumulator.updateLive("কাজলবুরর বাংলা ভয়েস টাইপিং")

        val flushed = accumulator.flushOnStop()
        assertNotNull(flushed)

        assertEquals(1, accumulator.liveHypothesisHistory.size)
        val group = accumulator.liveHypothesisHistory[0]
        assertEquals(2, group.hypotheses.size)
        assertEquals(true, group.isFinalized)
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", group.currentBest)
        assertEquals("কাজলবুরর বাংলা ভয়েস টাইপিং", accumulator.finalTranscript)
    }

    /**
     * Test 6: Compose Recomposition / UI State Updates
     * Expected: liveHypothesisHistory in SttUiState is preserved through state changes.
     */
    @Test
    fun test6_composeRecomposition_hypothesisHistoryUnchanged() {
        val group1 = LiveHypothesisGroup(
            id = 1L,
            hypotheses = listOf("বারিত্তে সব তাহা", "বাড়িতে সব টাকা পয়সা"),
            currentBest = "বাড়িতে সব টাকা পয়সা",
            isFinalized = true
        )

        var uiState = SttUiState(
            liveHypothesisHistory = listOf(group1),
            finalTranscript = "বাড়িতে সব টাকা পয়সা",
            liveTranscript = "এখন যাচ্ছি"
        )

        assertEquals(1, uiState.liveHypothesisHistory.size)
        assertEquals(2, uiState.liveHypothesisHistory[0].hypotheses.size)

        // Simulate Recompositions: audio level changes, tab change, collapse
        uiState = uiState.copy(rmsLevel = 0.85f)
        assertEquals(1, uiState.liveHypothesisHistory.size)

        uiState = uiState.copy(selectedTab = 1)
        assertEquals(1, uiState.liveHypothesisHistory.size)

        uiState = uiState.copy(isConfigCollapsed = false)
        assertEquals(1, uiState.liveHypothesisHistory.size)
    }

    /**
     * Test 7: User Explicit Clear
     * Expected: All hypothesis history and transcripts are cleared.
     */
    @Test
    fun test7_userClear_clearsAllHypothesisHistoryAndTranscripts() {
        accumulator.updateLive("প্রথম হাইপোথিসিস")
        accumulator.commitFinal("প্রথম হাইপোথিসিস সম্পন্ন")
        accumulator.updateLive("দ্বিতীয় হাইপোথিসিস")

        assertEquals(2, accumulator.liveHypothesisHistory.size)
        assertEquals("প্রথম হাইপোথিসিস সম্পন্ন", accumulator.finalTranscript)

        // User clicks Clear
        accumulator.clear()

        assertEquals(0, accumulator.liveHypothesisHistory.size)
        assertEquals("", accumulator.finalTranscript)
        assertEquals("", accumulator.liveTranscript)
        assertEquals("", accumulator.displayedTranscript)
        assertEquals(0, accumulator.finalizedSegments.size)
    }

    /**
     * Test 8: Unified TranscriptAccumulator Architecture
     * Both Live Mode and Audio File Mode work with the same accumulator and models.
     */
    @Test
    fun test8_unifiedAccumulatorArchitecture_liveAndFileModeIntegrity() {
        val liveAcc = TranscriptAccumulator()
        val fileAcc = TranscriptAccumulator()

        // Live transcription
        liveAcc.updateLive("লাইভ কথা ১")
        liveAcc.updateLive("লাইভ কথা ২")
        liveAcc.commitFinal("লাইভ কথা ২ সম্পন্ন")

        assertEquals(1, liveAcc.liveHypothesisHistory.size)
        assertEquals(3, liveAcc.liveHypothesisHistory[0].hypotheses.size)
        assertEquals("লাইভ কথা ২ সম্পন্ন", liveAcc.finalTranscript)

        // File transcription
        fileAcc.updateLive("ফাইল কথা ১")
        fileAcc.updateLive("ফাইল কথা ২")
        fileAcc.commitFinal("ফাইল কথা ২ সম্পন্ন")

        assertEquals(1, fileAcc.liveHypothesisHistory.size)
        assertEquals(3, fileAcc.liveHypothesisHistory[0].hypotheses.size)
        assertEquals("ফাইল কথা ২ সম্পন্ন", fileAcc.finalTranscript)
    }

    /**
     * Test: User selecting alternative hypothesis in group
     */
    @Test
    fun testHypothesisSelection_updatesCurrentBestAndFinalTranscript() {
        accumulator.updateLive("বারিত্তে সব তাহা পয়সা")
        accumulator.updateLive("বাড়িতে সব টাকা পয়সা")
        accumulator.commitFinal("বাড়িতে সব টাকা পয়সা")

        assertEquals(1, accumulator.liveHypothesisHistory.size)
        assertEquals("বাড়িতে সব টাকা পয়সা", accumulator.liveHypothesisHistory[0].currentBest)
        assertEquals("বাড়িতে সব টাকা পয়সা", accumulator.finalTranscript)

        // User chooses dialect option "বারিত্তে সব তাহা পয়সা"
        val groupId = accumulator.liveHypothesisHistory[0].id
        accumulator.selectHypothesis(groupId, "বারিত্তে সব তাহা পয়সা")

        assertEquals("বারিত্তে সব তাহা পয়সা", accumulator.liveHypothesisHistory[0].currentBest)
        assertEquals("বারিত্তে সব তাহা পয়সা", accumulator.finalTranscript)
    }
}
