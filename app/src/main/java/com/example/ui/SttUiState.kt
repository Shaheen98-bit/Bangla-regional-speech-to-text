package com.example.ui

data class SttUiState(
    val isModelImported: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val modelSizeFormatted: String = "0 MB",
    val isModelLoaded: Boolean = false,

    val isTokenizerImported: Boolean = false,
    val tokenizerSizeBytes: Long = 0L,
    val tokenizerSizeFormatted: String = "0 KB",
    val tokenizerVocabSize: Int = 0,
    val isTokenizerLoaded: Boolean = false,

    val isRecording: Boolean = false,
    val rmsLevel: Float = 0f,

    // Transcription Text Buffer
    val liveTranscript: String = "",
    val fullTranscript: String = "",

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

    // Performance & Benchmark Metrics
    val audioWindowMs: Long = 0L,
    val featureShape: String = "[1, 80, 0]",
    val preprocessTimeMs: Long = 0L,
    val inferenceTimeMs: Long = 0L,
    val ctcTimeMs: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val rtf: Float = 0.0f,
    val blankIndex: Int = 128,

    // Diagnostic Mode
    val isDiagnosticRunning: Boolean = false,
    val diagnosticLogs: List<String> = emptyList(),
    val diagnosticSuccess: Boolean? = null,
    val showDiagnosticDialog: Boolean = false,

    // Error / Notification
    val userMessage: String? = null
) {
    val isBothReady: Boolean
        get() = isModelImported && isTokenizerImported
}
