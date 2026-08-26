package com.example.engine

import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified Thread-Safe TranscriptAccumulator.
 * Single source of truth for both Live Microphone and Audio File transcription.
 *
 * Maintains three distinct states:
 * 1. finalTranscript (finalizedSegments) - Persistent, immutable append-only history.
 * 2. liveTranscript (currentUtterance) - In-flight partial text for the active utterance only.
 * 3. liveHypothesisHistory (List<LiveHypothesisGroup>) - Full, persistent record of all meaningful
 *    hypotheses generated per voice chunk/utterance so regional dialect alternatives are never lost.
 *
 * Thread-safe: All mutations are synchronized to prevent concurrent double-commits or data races.
 */
class TranscriptAccumulator {

    private val lock = Any()

    private val _finalizedSegments = mutableListOf<TranscriptSegment>()
    private val _liveHypothesisHistory = mutableListOf<LiveHypothesisGroup>()
    private var _liveTranscript: String = ""
    private var _stablePrefix: String = ""
    private val recentHypotheses = mutableListOf<String>()

    private val segmentIdCounter = AtomicLong(1L)
    private val groupIdCounter = AtomicLong(1L)
    private var lastCommittedUtteranceId: Long = -1L
    private var lastCommittedNormalizedText: String = ""

    val finalizedSegments: List<TranscriptSegment>
        get() = synchronized(lock) { _finalizedSegments.toList() }

    val liveHypothesisHistory: List<LiveHypothesisGroup>
        get() = synchronized(lock) { _liveHypothesisHistory.toList() }

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
     * original formatted Bengali text is preserved in the segment and hypotheses.
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
     * Records every distinct meaningful hypothesis into the active LiveHypothesisGroup.
     * Never alters finalTranscript.
     */
    fun updateLive(partialHypothesis: String): String = synchronized(lock) {
        val trimmed = partialHypothesis.trim()
        if (trimmed.isEmpty()) {
            return _liveTranscript
        }

        val normCandidate = normalizeForComparison(trimmed)

        // Live Hypothesis Group tracking
        val activeGroupIndex = _liveHypothesisHistory.indexOfLast { !it.isFinalized }
        if (activeGroupIndex >= 0) {
            val currentGroup = _liveHypothesisHistory[activeGroupIndex]
            // Add if not already identical to the last hypothesis or contained with same normalized text
            val isDuplicate = currentGroup.hypotheses.isNotEmpty() &&
                    (currentGroup.hypotheses.last() == trimmed ||
                     currentGroup.hypotheses.any { normalizeForComparison(it) == normCandidate })

            val updatedList = if (!isDuplicate) {
                currentGroup.hypotheses + trimmed
            } else {
                currentGroup.hypotheses
            }

            _liveHypothesisHistory[activeGroupIndex] = currentGroup.copy(
                hypotheses = updatedList,
                currentBest = trimmed
            )
        } else {
            // Create a new active group for this in-flight voice chunk
            val newGroup = LiveHypothesisGroup(
                id = groupIdCounter.getAndIncrement(),
                startedAt = System.currentTimeMillis(),
                hypotheses = listOf(trimmed),
                currentBest = trimmed,
                isFinalized = false
            )
            _liveHypothesisHistory.add(newGroup)
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
     * Validates, checks for duplicates, appends to finalTranscript, finalizes active LiveHypothesisGroup,
     * and clears liveTranscript.
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
            val activeIndex = _liveHypothesisHistory.indexOfLast { !it.isFinalized }
            if (activeIndex >= 0) {
                val group = _liveHypothesisHistory[activeIndex]
                if (group.hypotheses.isEmpty()) {
                    _liveHypothesisHistory.removeAt(activeIndex)
                } else {
                    _liveHypothesisHistory[activeIndex] = group.copy(isFinalized = true)
                }
            }
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
            val activeIndex = _liveHypothesisHistory.indexOfLast { !it.isFinalized }
            if (activeIndex >= 0) {
                _liveHypothesisHistory[activeIndex] = _liveHypothesisHistory[activeIndex].copy(isFinalized = true)
            }
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

        // Finalize LiveHypothesisGroup
        val activeIndex = _liveHypothesisHistory.indexOfLast { !it.isFinalized }
        if (activeIndex >= 0) {
            val group = _liveHypothesisHistory[activeIndex]
            val isDuplicate = group.hypotheses.isNotEmpty() &&
                    (group.hypotheses.last() == textToCommit ||
                     group.hypotheses.any { normalizeForComparison(it) == normTextToCommit })
            val updatedList = if (!isDuplicate) group.hypotheses + textToCommit else group.hypotheses
            _liveHypothesisHistory[activeIndex] = group.copy(
                hypotheses = updatedList,
                currentBest = textToCommit,
                isFinalized = true
            )
        } else {
            // Group didn't exist (e.g. direct commitFinal call), create one
            val newGroup = LiveHypothesisGroup(
                id = groupIdCounter.getAndIncrement(),
                startedAt = if (startMs > 0) startMs else System.currentTimeMillis(),
                hypotheses = listOf(textToCommit),
                currentBest = textToCommit,
                isFinalized = true
            )
            _liveHypothesisHistory.add(newGroup)
        }

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
     * Commits any pending liveTranscript to finalTranscript, finalizes the active group,
     * and clears live state.
     */
    fun flushOnStop(startMs: Long = 0L, endMs: Long = 0L): TranscriptSegment? = synchronized(lock) {
        val activeIndex = _liveHypothesisHistory.indexOfLast { !it.isFinalized }
        if (_liveTranscript.isBlank()) {
            _stablePrefix = ""
            recentHypotheses.clear()
            if (activeIndex >= 0) {
                _liveHypothesisHistory[activeIndex] = _liveHypothesisHistory[activeIndex].copy(isFinalized = true)
            }
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
     * Allows user to choose a specific hypothesis in a chunk group.
     * Updates currentBest and synchronizes finalTranscript segments.
     */
    fun selectHypothesis(groupId: Long, selectedText: String) = synchronized(lock) {
        val index = _liveHypothesisHistory.indexOfFirst { it.id == groupId }
        if (index >= 0) {
            val group = _liveHypothesisHistory[index]
            _liveHypothesisHistory[index] = group.copy(currentBest = selectedText)

            if (index < _finalizedSegments.size) {
                val oldSeg = _finalizedSegments[index]
                _finalizedSegments[index] = oldSeg.copy(text = selectedText)
            }
        }
    }

    /**
     * Clears both finalTranscript, liveTranscript, and liveHypothesisHistory.
     * Only triggered when the user explicitly clicks Clear.
     */
    fun clear() = synchronized(lock) {
        _finalizedSegments.clear()
        _liveHypothesisHistory.clear()
        _liveTranscript = ""
        _stablePrefix = ""
        recentHypotheses.clear()
        lastCommittedUtteranceId = -1L
        lastCommittedNormalizedText = ""
    }
}
