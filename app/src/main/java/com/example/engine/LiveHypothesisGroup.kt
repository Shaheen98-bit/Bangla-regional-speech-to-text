package com.example.engine

/**
 * Represents a group of transcription hypotheses generated during inference
 * for a single voice utterance / audio chunk.
 *
 * Prevents intermediate hypotheses from being overwritten or lost in regional
 * dialect streaming where multiple meaningful alternatives may be generated.
 */
data class LiveHypothesisGroup(
    val id: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val hypotheses: List<String> = emptyList(),
    val currentBest: String = "",
    val isFinalized: Boolean = false
) {
    /**
     * Helper to return all distinct meaningful hypotheses for this chunk.
     */
    val hypothesisCount: Int get() = hypotheses.size

    /**
     * Backward-compatible helper to get the primary text of this chunk.
     */
    val displayText: String get() = if (currentBest.isNotEmpty()) currentBest else hypotheses.lastOrNull() ?: ""
}
