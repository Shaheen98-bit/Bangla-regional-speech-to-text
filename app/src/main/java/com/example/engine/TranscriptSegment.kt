package com.example.engine

/**
 * Immutable representation of a finalized or in-progress transcript segment.
 * Each finalized segment has a unique monotonically increasing ID.
 */
data class TranscriptSegment(
    val id: Long,
    val text: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val finalized: Boolean = true
)
