package com.example.engine

import java.text.Normalizer

/**
 * Handles streaming CTC transcript accumulation, stable-prefix detection,
 * Bengali text normalization, overlap/duplicate prevention, and safe append-only commit.
 */
class StreamingTranscriptAccumulator {

    private val finalizedUtterances = mutableListOf<String>()
    private var _currentPartial: String = ""
    private var _stablePrefix: String = ""
    private var recentHypotheses = mutableListOf<String>()

    val fullTranscript: String
        get() = finalizedUtterances.joinToString("\n")

    val currentPartial: String
        get() = _currentPartial

    val stablePrefix: String
        get() = _stablePrefix

    val utteranceCount: Int
        get() = finalizedUtterances.size

    /**
     * Normalizes Bengali text:
     * - Unicode NFC normalization
     * - Removes zero-width characters (ZWJ \u200D, ZWNJ \u200C, zero-width space \u200B)
     * - Normalizes multiple whitespace into single spaces
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
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Calculates the longest common word prefix between two strings.
     */
    fun findStableWordPrefix(s1: String, s2: String): String {
        val norm1 = normalizeBengali(s1)
        val norm2 = normalizeBengali(s2)
        if (norm1.isEmpty() || norm2.isEmpty()) return ""

        val words1 = norm1.split(" ")
        val words2 = norm2.split(" ")
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
     * Processes an intermediate streaming hypothesis for the active utterance.
     * Computes the stable prefix across consecutive windows and updates currentPartial.
     */
    fun updatePartial(newHypothesis: String): String {
        val cleanHypo = normalizeBengali(newHypothesis)
        if (cleanHypo.isEmpty()) {
            return _currentPartial
        }

        recentHypotheses.add(cleanHypo)
        if (recentHypotheses.size > 5) {
            recentHypotheses.removeAt(0)
        }

        // Compute stable prefix across the last 2-3 hypotheses
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

        _currentPartial = cleanHypo
        return _currentPartial
    }

    /**
     * Finds and strips overlapping words between the end of fullTranscript and the start of candidateText.
     */
    fun removeOverlapWithHistory(candidateText: String): String {
        val cleanCandidate = normalizeBengali(candidateText)
        if (cleanCandidate.isEmpty() || finalizedUtterances.isEmpty()) {
            return cleanCandidate
        }

        val lastCommitted = normalizeBengali(finalizedUtterances.last())
        if (lastCommitted.isEmpty()) return cleanCandidate

        // Exact match or candidate is fully contained in last committed -> duplicate!
        if (cleanCandidate == lastCommitted) {
            return ""
        }

        val lastWords = lastCommitted.split(" ")
        val candidateWords = cleanCandidate.split(" ")

        // Check if candidate starts with a suffix of lastWords
        // e.g. lastWords = ["আমি", "বাংলায়", "গান", "গাই"], candidate = ["গান", "গাই", "প্রতিদিন"]
        val maxOverlapCheck = minOf(lastWords.size, candidateWords.size)
        for (overlapLen in maxOverlapCheck downTo 1) {
            val lastSuffix = lastWords.takeLast(overlapLen)
            val candidatePrefix = candidateWords.take(overlapLen)

            if (lastSuffix == candidatePrefix) {
                val remainingWords = candidateWords.drop(overlapLen)
                return remainingWords.joinToString(" ")
            }
        }

        // Also check if candidate is a substring of lastCommitted
        if (lastCommitted.contains(cleanCandidate)) {
            return ""
        }

        return cleanCandidate
    }

    /**
     * Finalizes the current utterance on VAD pause / boundary.
     * Returns the committed text string (empty if duplicate/filtered).
     */
    fun finalizeUtterance(rawText: String): String {
        val cleanText = normalizeBengali(rawText)
        val textToCommit = if (cleanText.isNotEmpty()) {
            cleanText
        } else if (_currentPartial.isNotEmpty()) {
            _currentPartial
        } else {
            ""
        }

        // Overlap / duplicate check
        val nonOverlappingText = removeOverlapWithHistory(textToCommit)

        var committedText = ""
        if (nonOverlappingText.isNotEmpty()) {
            finalizedUtterances.add(nonOverlappingText)
            committedText = nonOverlappingText
        }

        // Reset utterance-level tracking
        _currentPartial = ""
        _stablePrefix = ""
        recentHypotheses.clear()

        return committedText
    }

    /**
     * Flushes remaining partial text when recording stops.
     * Commits remaining non-empty, non-overlapping partial exactly once.
     */
    fun flushOnStop(): String {
        if (_currentPartial.isEmpty()) {
            _stablePrefix = ""
            recentHypotheses.clear()
            return ""
        }

        val textToCommit = _currentPartial
        val nonOverlappingText = removeOverlapWithHistory(textToCommit)

        var committedText = ""
        if (nonOverlappingText.isNotEmpty()) {
            finalizedUtterances.add(nonOverlappingText)
            committedText = nonOverlappingText
        }

        _currentPartial = ""
        _stablePrefix = ""
        recentHypotheses.clear()
        return committedText
    }

    /**
     * Resets all state including fullTranscript.
     */
    fun clear() {
        finalizedUtterances.clear()
        _currentPartial = ""
        _stablePrefix = ""
        recentHypotheses.clear()
    }
}
