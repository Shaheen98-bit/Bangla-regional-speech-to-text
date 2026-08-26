package com.example.engine

import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified Thread-Safe TranscriptAccumulator.
 * Single source of truth for both Live Microphone and Audio File transcription.
 *
 * Maintains two distinct states:
 * 1. finalTranscript (finalizedSegments) - Persistent, immutable append-only history.
 * 2. liveTranscript (currentUtterance) - In-flight partial text for the active utterance only.
 *
 * Thread-safe: All mutations are synchronized to prevent concurrent double-commits.
 */
class TranscriptAccumulator {

    private val lock = Any()

    private val _finalizedSegments = mutableListOf<TranscriptSegment>()
    private var _liveTranscript: String = ""
    private var _stablePrefix: String = ""
    private val recentHypotheses = mutableListOf<String>()

    private val segmentIdCounter = AtomicLong(1L)
    private var lastCommittedUtteranceId: Long = -1L
    private var lastCommittedNormalizedText: String = ""

    val finalizedSegments: List<TranscriptSegment>
        get() = synchronized(lock) { _finalizedSegments.toList() }

    val finalTranscript: String
        get() = synchronized(lock) { _finalizedSegments.joinToString("\n") { it.text } }

    val liveTranscript: String
        get() = synchronized(lock) { _liveTranscript }

    // Combined display representation: finalTranscript + liveTranscript
    val displayedTranscript: String
        get() = synchronized(lock) {
            val finalT = finalTranscript
            val liveT = _liveTranscript
            when {
                finalT.isEmpty() -> liveT
                liveT.isEmpty() -> finalT
                else -> "$finalT\n$liveT"
            }
        }

    // Backward-compatibility accessors
    val committedTranscript: String get() = finalTranscript
    val fullTranscript: String get() = finalTranscript
    val currentUtterance: String get() = liveTranscript
    val interimText: String get() = liveTranscript
    val currentPartial: String get() = liveTranscript
    val stablePrefix: String get() = synchronized(lock) { _stablePrefix }
    val utteranceCount: Int get() = synchronized(lock) { _finalizedSegments.size }
    val lastCommittedText: String get() = synchronized(lock) { _finalizedSegments.lastOrNull()?.text ?: "" }

    /**
     * Normalizes Bengali text for duplicate and overlap detection.
     * Note: Normalization is used ONLY for comparison and overlap detection;
     * original formatted Bengali text is preserved in the segment.
     */
    fun normalizeForComparison(text: String): String {
        if (text.isEmpty()) return ""
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return nfc
            .replace("\u200C", "") // ZWNJ
            .replace("\u200D", "") // ZWJ
            .replace("\u200B", "") // ZWSP
            .replace("\uFEFF", "") // BOM
            .replace(Regex("[\\p{Punct}&&[^।]]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Alias for backward compatibility.
     */
    fun normalizeBengali(text: String): String = normalizeForComparison(text)

    /**
     * Calculates the longest common word prefix between two hypotheses.
     */
    fun findStableWordPrefix(s1: String, s2: String): String {
        val norm1 = normalizeForComparison(s1)
        val norm2 = normalizeForComparison(s2)
        if (norm1.isEmpty() || norm2.isEmpty()) return ""

        val words1 = norm1.split(" ").filter { it.isNotBlank() }
        val words2 = norm2.split(" ").filter { it.isNotBlank() }
        val commonWords = mutableListOf<String>()

        val minSize = minOf(words1.size, words2.size)
        for (i in 0 until minSize) {
            if (words1[i] == words2[i]) {
                commonWords.add(words1[i])
            } else {
                break
            }
        }
        return commonWords.joinToString(" ")
    }

    /**
     * Strips overlapping word suffix/prefix between the previous committed segment and new candidate text.
     */
    fun removeSuffixPrefixOverlap(previousText: String, candidateText: String): String {
        val prevNorm = normalizeForComparison(previousText)
        val candNorm = normalizeForComparison(candidateText)

        if (candNorm.isEmpty()) return ""
        if (prevNorm.isEmpty()) return candidateText.trim()

        // Exact match or candidate is fully contained in previous -> duplicate!
        if (candNorm == prevNorm || prevNorm.endsWith(candNorm) || prevNorm.contains(candNorm)) {
            return ""
        }

        val prevWords = prevNorm.split(" ").filter { it.isNotBlank() }
        val candWords = candNorm.split(" ").filter { it.isNotBlank() }

        val maxOverlap = minOf(prevWords.size, candWords.size)
        for (len in maxOverlap downTo 1) {
            val prevSuffix = prevWords.takeLast(len)
            val candPrefix = candWords.take(len)

            if (prevSuffix == candPrefix) {
                // Drop the first `len` words from candidate
                val rawWords = candidateText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val remaining = rawWords.drop(len)
                return remaining.joinToString(" ")
            }
        }

        return candidateText.trim()
    }

    /**
     * Alias for backward compatibility with existing tests.
     */
    fun removeOverlapWithHistory(candidateText: String): String {
        val lastCommitted = lastCommittedText
        return removeSuffixPrefixOverlap(lastCommitted, candidateText)
    }

    /**
     * Updates the in-flight liveTranscript for the active utterance.
     * Never alters finalTranscript.
     */
    fun updateLive(partialHypothesis: String): String = synchronized(lock) {
        val trimmed = partialHypothesis.trim()
        if (trimmed.isEmpty()) {
            return _liveTranscript
        }

        recentHypotheses.add(trimmed)
        if (recentHypotheses.size > 5) {
            recentHypotheses.removeAt(0)
        }

        if (recentHypotheses.size >= 2) {
            var prefix = recentHypotheses.last()
            for (i in recentHypotheses.size - 2 downTo 0) {
                prefix = findStableWordPrefix(prefix, recentHypotheses[i])
                if (prefix.isEmpty()) break
            }
            _stablePrefix = prefix
        } else {
            _stablePrefix = ""
        }

        _liveTranscript = trimmed
        _liveTranscript
    }

    /**
     * Backward-compatible aliases for updateLive.
     */
    fun updateInterim(newHypothesis: String, utteranceId: Long = 0L): String = updateLive(newHypothesis)
    fun updatePartial(newHypothesis: String): String = updateLive(newHypothesis)

    /**
     * Commits a finalized utterance to finalTranscript atomically.
     * Validates, checks for duplicates, appends to finalTranscript, and clears liveTranscript.
     */
    fun commitFinal(
        rawText: String = "",
        utteranceId: Long = 0L,
        startMs: Long = 0L,
        endMs: Long = 0L
    ): TranscriptSegment? = synchronized(lock) {
        val candidate = if (rawText.isNotBlank()) rawText.trim() else _liveTranscript.trim()
        val normCandidate = normalizeForComparison(candidate)

        // Clear in-flight live state
        _liveTranscript = ""
        _stablePrefix = ""
        recentHypotheses.clear()

        // 1. Reject empty/whitespace text
        if (normCandidate.isEmpty()) {
            return null
        }

        // 2. Reject duplicate commit of the same utterance ID
        if (utteranceId > 0 && utteranceId == lastCommittedUtteranceId) {
            return null
        }

        // 3. Reject duplicate text matching the immediately previous committed segment
        val lastCommitted = _finalizedSegments.lastOrNull()?.text ?: ""
        val textToCommit = removeSuffixPrefixOverlap(lastCommitted, candidate)
        val normTextToCommit = normalizeForComparison(textToCommit)

        if (normTextToCommit.isEmpty() || normTextToCommit == lastCommittedNormalizedText) {
            return null
        }

        val segment = TranscriptSegment(
            id = segmentIdCounter.getAndIncrement(),
            text = textToCommit,
            startMs = startMs,
            endMs = endMs,
            finalized = true
        )

        _finalizedSegments.add(segment)
        if (utteranceId > 0) {
            lastCommittedUtteranceId = utteranceId
        }
        lastCommittedNormalizedText = normTextToCommit

        segment
    }

    /**
     * Backward-compatible alias for commitFinal.
     */
    fun commitUtterance(
        rawText: String = "",
        utteranceId: Long = 0L,
        startMs: Long = 0L,
        endMs: Long = 0L
    ): TranscriptSegment? = commitFinal(rawText, utteranceId, startMs, endMs)

    fun finalizeUtterance(rawText: String): String {
        val segment = commitFinal(rawText)
        return segment?.text ?: ""
    }

    /**
     * When recording stops or audio file completes:
     * Commits any pending liveTranscript to finalTranscript and clears live state.
     */
    fun flushOnStop(startMs: Long = 0L, endMs: Long = 0L): TranscriptSegment? = synchronized(lock) {
        if (_liveTranscript.isBlank()) {
            _stablePrefix = ""
            recentHypotheses.clear()
            return null
        }

        val textToFlush = _liveTranscript
        val segment = commitFinal(rawText = textToFlush, utteranceId = 0L, startMs = startMs, endMs = endMs)
        _liveTranscript = ""
        _stablePrefix = ""
        recentHypotheses.clear()
        segment
    }

    /**
     * Clears both finalTranscript and liveTranscript.
     * Only triggered when the user explicitly clicks Clear.
     */
    fun clear() = synchronized(lock) {
        _finalizedSegments.clear()
        _liveTranscript = ""
        _stablePrefix = ""
        recentHypotheses.clear()
        lastCommittedUtteranceId = -1L
        lastCommittedNormalizedText = ""
    }
}
