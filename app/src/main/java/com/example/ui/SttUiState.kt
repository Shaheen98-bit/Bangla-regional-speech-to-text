package com.example.ui

/**
 * Immutable UI State representing STT session status, accumulator buffers, and telemetry.
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

    // Transcription Text Buffers
    // fullTranscript is a permanent append-only finalized transcript (NEVER overwritten by partial)
    val fullTranscript: String = "",
    // currentPartial represents the intermediate hypothesis for the active in-flight utterance
    val currentPartial: String = "",
    val liveTranscript: String = "", // Mirror for currentPartial for UI/backward compatibility
    val stablePrefix: String = "",
    val lastCommittedText: String = "",

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
}
