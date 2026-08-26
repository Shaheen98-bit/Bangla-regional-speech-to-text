package com.example.ui

import com.example.engine.LiveHypothesisGroup
import com.example.engine.TranscriptSegment

/**
 * Immutable UI State representing STT session status, accumulator buffers, hypothesis history, and telemetry.
 *
 * Implements the Unified Google Live Transcribe architecture with Regional Hypothesis History:
 * 1. finalTranscript / finalizedSegments - Persistent, immutable append-only history.
 * 2. liveTranscript / currentUtterance - In-flight partial text for the active utterance only.
 * 3. liveHypothesisHistory - Full persistent list of LiveHypothesisGroups retaining all intermediate
 *    regional dialect hypotheses per voice chunk so they are never silently overwritten or discarded.
 * 4. displayedTranscript - Clean combination of finalTranscript + liveTranscript.
 */
data class SttUiState(
    // Model and Tokenizer State
    val isModelImported: Boolean = false,
    val isTokenizerImported: Boolean = false,
    val isModelLoaded: Boolean = false,
    val isTokenizerLoaded: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val modelSizeFormatted: String = "",
    val tokenizerSizeBytes: Long = 0L,
    val tokenizerSizeFormatted: String = "",
    val tokenizerVocabSize: Int = 0,
    val blankIndex: Int = 128,

    // Runtime State
    val isRecording: Boolean = false,
    val rmsLevel: Float = 0f,

    // Live Transcription Buffers & Hypothesis History
    val finalizedSegments: List<TranscriptSegment> = emptyList(),
    val liveHypothesisHistory: List<LiveHypothesisGroup> = emptyList(),
    val finalTranscript: String = "",
    val liveTranscript: String = "",
    val stablePrefix: String = "",
    val lastCommittedText: String = "",

    // Backward compatibility aliases
    val currentUtterance: String = liveTranscript,
    val interimText: String = liveTranscript,
    val fullTranscript: String = finalTranscript,
    val currentPartial: String = liveTranscript,

    // Frame and VAD Telemetry
    val audioFrameStart: Long = 0L,
    val audioFrameEnd: Long = 0L,
    val vadState: String = "IDLE",

    // UI View & Layout Controls
    val isConfigCollapsed: Boolean = true,
    val selectedTab: Int = 0, // 0 = Live Speech, 1 = Audio File

    // Audio File Transcription State
    val isTranscribingFile: Boolean = false,
    val fileTranscriptionProgress: Float = 0f,
    val fileTranscriptionStatus: String = "",
    val selectedAudioFileName: String = "",
    val fileFinalizedSegments: List<TranscriptSegment> = emptyList(),
    val fileTranscript: String = "",

    // Import progress
    val isImporting: Boolean = false,
    val importFileName: String = "",
    val importProgressFraction: Float = 0f,
    val importStatusText: String = "",

    // Diagnostics Modal
    val showDiagnosticDialog: Boolean = false,
    val diagnosticLogs: List<String> = emptyList(),
    val isDiagnosticRunning: Boolean = false,
    val diagnosticSuccess: Boolean? = null,

    // Performance & Benchmark Telemetry
    val audioWindowMs: Long = 0L,
    val featureShape: String = "",
    val preprocessTimeMs: Long = 0L,
    val inferenceTimeMs: Long = 0L,
    val ctcTimeMs: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val rtf: Float = 0f,

    // User Feedback
    val userMessage: String? = null
) {
    val isBothReady: Boolean
        get() = isModelImported && isTokenizerImported

    val hasHypothesisHistory: Boolean
        get() = liveHypothesisHistory.isNotEmpty()

    val displayedTranscript: String
        get() = when {
            finalTranscript.isEmpty() -> liveTranscript
            liveTranscript.isEmpty() -> finalTranscript
            else -> "$finalTranscript\n$liveTranscript"
        }

    val committedTranscript: String
        get() = if (finalTranscript.isNotEmpty()) finalTranscript else finalizedSegments.joinToString("\n") { it.text }
}
