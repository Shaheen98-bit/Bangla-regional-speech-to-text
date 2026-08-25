package com.example.engine

import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Google Live Transcribe-style transcript accumulator.
 *
 * Maintains three strictly separated states:
 * 1. committedTranscript (finalizedSegments: List<TranscriptSegment>) - Immutable append-only history.
 * 2. currentUtterance (interimText: String) - Active in-flight speech segment only.
 * 3. pendingCommit / commit guard - Deduplication, Bengali normalization, and single-commit per utterance.
 */
class StreamingTranscriptAccumulator {

    private val _finalizedSegments = mutableListOf<TranscriptSegment>()
    private var _currentUtterance: String = ""
    private var _stablePrefix: String = ""
    private val recentHypotheses = mutableListOf<String>()

    private val segmentIdCounter = AtomicLong(1L)
    private var lastCommittedUtteranceId: Long = -1L
    private var lastCommittedNormalizedText: String = ""

    val finalizedSegments: List<TranscriptSegment>
        get() = _finalizedSegments.toList()

    val committedTranscript: String
        get() = _finalizedSegments.joinToString("\n") { it.text }

    val fullTranscript: String
        get() = committedTranscript

    val currentUtterance: String
        get() = _currentUtterance

    val interimText: String
        get() = _currentUtterance

    val currentPartial: String
        get() = _currentUtterance

    val stablePrefix: String
        get() = _stablePrefix

    val utteranceCount: Int
        get() = _finalizedSegments.size

    val lastCommittedText: String
        get() = _finalizedSegments.lastOrNull()?.text ?: ""

    /**
     * Normalizes Bengali text:
     * - Unicode NFC normalization
     * - Removes zero-width characters (ZWJ \u200D, ZWNJ \u200C, zero-width space \u200B, BOM \uFEFF)
     * - Normalizes multiple whitespace into single space
     * - Trims leading/trailing whitespace
     */
    fun normalizeBengali(text: String): String {
        if (text.isEmpty()) return ""
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return nfc
            .replace("\u200C", "") // ZWNJ
            .replace("\u200D", "") // ZWJ
            .replace("\u200B", "") // ZWSP
            .replace("\uFEFF", "") // BOM
            .replace(Regex("[\\p{Punct}&&[^।]]+"), " ") // normalize ASCII punctuation
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Calculates the longest common word prefix between two hypotheses.
     */
    fun findStableWordPrefix(s1: String, s2: String): String {
        val norm1 = normalizeBengali(s1)
        val norm2 = normalizeBengali(s2)
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
     * Removes overlapping word suffix/prefix between previous text and current candidate text.
     * E.g. "আমি আজ ঢাকায় গেলাম" + "ঢাকায় গেলাম তারপর বাজারে গেলাম" -> "তারপর বাজারে গেলাম"
     */
    fun removeSuffixPrefixOverlap(previousText: String, candidateText: String): String {
        val prevNorm = normalizeBengali(previousText)
        val candNorm = normalizeBengali(candidateText)

        if (candNorm.isEmpty()) return ""
        if (prevNorm.isEmpty()) return candNorm

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
                val remaining = candWords.drop(len)
                return remaining.joinToString(" ")
            }
        }

        return candNorm
    }

    /**
     * Updates the interim hypothesis for the currently active utterance.
     * Updates ONLY currentUtterance / interimText. NEVER touches finalizedSegments.
     */
    fun updateInterim(newHypothesis: String, utteranceId: Long = 0L): String {
        val cleanHypo = normalizeBengali(newHypothesis)
        if (cleanHypo.isEmpty()) {
            return _currentUtterance
        }

        recentHypotheses.add(cleanHypo)
        if (recentHypotheses.size > 5) {
            recentHypotheses.removeAt(0)
        }

        // Calculate stable prefix across the last consecutive hypotheses
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

        _currentUtterance = cleanHypo
        return _currentUtterance
    }

    /**
     * Alias for updateInterim for backward compatibility with existing tests/callers.
     */
    fun updatePartial(newHypothesis: String): String = updateInterim(newHypothesis, 0L)

    /**
     * Commits the current utterance to finalizedSegments exactly once on VAD pause or boundary.
     * Rejects duplicates, empty text, or repeat commits of the same utterance ID.
     */
    fun commitUtterance(
        rawText: String = "",
        utteranceId: Long = 0L,
        startMs: Long = 0L,
        endMs: Long = 0L
    ): TranscriptSegment? {
        val candidate = if (rawText.isNotBlank()) rawText else _currentUtterance
        val cleanCandidate = normalizeBengali(candidate)

        // Reset interim state regardless
        _currentUtterance = ""
        _stablePrefix = ""
        recentHypotheses.clear()

        if (cleanCandidate.isEmpty()) {
            return null
        }

        // Guard 1: Prevent committing the same utteranceId multiple times
        if (utteranceId > 0 && utteranceId == lastCommittedUtteranceId) {
            return null
        }

        // Guard 2: Overlap / duplicate check against immediately previous finalized segment
        val lastCommitted = _finalizedSegments.lastOrNull()?.text ?: ""
        val textToCommit = removeSuffixPrefixOverlap(lastCommitted, cleanCandidate)

        if (textToCommit.isEmpty()) {
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
        lastCommittedNormalizedText = normalizeBengali(textToCommit)

        return segment
    }

    /**
     * Backward-compatible alias for finalizeUtterance returning committed text.
     */
    fun finalizeUtterance(rawText: String): String {
        val segment = commitUtterance(rawText)
        return segment?.text ?: ""
    }

    /**
     * Flushes remaining in-flight interim speech when user stops recording.
     * Commits once and clears interim state.
     */
    fun flushOnStop(startMs: Long = 0L, endMs: Long = 0L): TranscriptSegment? {
        if (_currentUtterance.isEmpty()) {
            _stablePrefix = ""
            recentHypotheses.clear()
            return null
        }

        val textToFlush = _currentUtterance
        val segment = commitUtterance(rawText = textToFlush, utteranceId = 0L, startMs = startMs, endMs = endMs)
        _currentUtterance = ""
        _stablePrefix = ""
        recentHypotheses.clear()
        return segment
    }

    /**
     * Explicit user clear action.
     */
    fun clear() {
        _finalizedSegments.clear()
        _currentUtterance = ""
        _stablePrefix = ""
        recentHypotheses.clear()
        lastCommittedUtteranceId = -1L
        lastCommittedNormalizedText = ""
    }
}
