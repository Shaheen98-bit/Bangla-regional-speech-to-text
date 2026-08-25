package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecorderManager
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.engine.CtcDecoder
import com.example.engine.OnnxAsrEngine
import com.example.model.ModelManager
import com.example.tokenizer.SentencePieceTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SttViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SttViewModel"
    }

    val modelManager = ModelManager(application.applicationContext)
    private val preprocessor = MelSpectrogramPreprocessor()
    private val onnxEngine = OnnxAsrEngine()
    private val ctcDecoder = CtcDecoder(blankIndex = 128)
    private var tokenizer: SentencePieceTokenizer? = null

    private val audioRecorder = AudioRecorderManager(
        sampleRate = 16000,
        windowDurationMs = 2500, // 2.5s rolling window
        stepDurationMs = 500     // 0.5s update interval
    )

    private val _uiState = MutableStateFlow(SttUiState())
    val uiState = _uiState.asStateFlow()

    private val isInferring = AtomicBoolean(false)
    private var lastDecodedText = ""
    private var committedHistory = StringBuilder()

    init {
        checkPersistedFilesAndInitialize()
        observeImportProgress()
        setupAudioListener()
    }

    private fun observeImportProgress() {
        viewModelScope.launch {
            modelManager.importProgress.collect { progress ->
                _uiState.update { current ->
                    current.copy(
                        isImporting = progress.isImporting,
                        importFileName = progress.fileName,
                        importProgressFraction = progress.progressFraction,
                        importStatusText = progress.statusMessage
                    )
                }
            }
        }
    }

    private fun checkPersistedFilesAndInitialize() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelReady = modelManager.isModelReady()
            val tokenizerReady = modelManager.isTokenizerReady()

            val modelBytes = modelManager.getModelSizeBytes()
            val tokenizerBytes = modelManager.getTokenizerSizeBytes()

            _uiState.update {
                it.copy(
                    isModelImported = modelReady,
                    modelSizeBytes = modelBytes,
                    modelSizeFormatted = modelManager.formatFileSize(modelBytes),
                    isTokenizerImported = tokenizerReady,
                    tokenizerSizeBytes = tokenizerBytes,
                    tokenizerSizeFormatted = modelManager.formatFileSize(tokenizerBytes)
                )
            }

            if (tokenizerReady) {
                loadTokenizerInternal()
            }
            if (modelReady) {
                loadModelInternal()
            }
        }
    }

    private suspend fun loadTokenizerInternal(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = modelManager.tokenizerFile
            if (file.exists()) {
                val tok = SentencePieceTokenizer.fromFile(file)
                tokenizer = tok
                ctcDecoder.updateBlankIndexFromVocab(tok.vocabSize, 129)

                _uiState.update {
                    it.copy(
                        isTokenizerLoaded = true,
                        tokenizerVocabSize = tok.vocabSize,
                        blankIndex = ctcDecoder.blankIndex
                    )
                }
                Log.i(TAG, "Tokenizer loaded with vocab size: ${tok.vocabSize}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading tokenizer", e)
            _uiState.update { it.copy(isTokenizerLoaded = false, userMessage = "Tokenizer error: ${e.message}") }
            false
        }
    }

    private suspend fun loadModelInternal(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = modelManager.modelFile
            if (file.exists() && file.length() > 1000) {
                val result = onnxEngine.loadModel(file, numThreads = 2)
                if (result.isSuccess) {
                    _uiState.update { it.copy(isModelLoaded = true) }
                    Log.i(TAG, "ONNX model loaded successfully.")
                    true
                } else {
                    _uiState.update {
                        it.copy(
                            isModelLoaded = false,
                            userMessage = "Failed to load ONNX: ${result.exceptionOrNull()?.message}"
                        )
                    }
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ONNX model", e)
            _uiState.update { it.copy(isModelLoaded = false, userMessage = "Model error: ${e.message}") }
            false
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val result = modelManager.importModelFromUri(uri)
            if (result.isSuccess) {
                val bytes = modelManager.getModelSizeBytes()
                _uiState.update {
                    it.copy(
                        isModelImported = true,
                        modelSizeBytes = bytes,
                        modelSizeFormatted = modelManager.formatFileSize(bytes),
                        userMessage = "Model imported successfully!"
                    )
                }
                loadModelInternal()
            } else {
                _uiState.update {
                    it.copy(
                        userMessage = "Failed to import model: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    fun importTokenizer(uri: Uri) {
        viewModelScope.launch {
            val result = modelManager.importTokenizerFromUri(uri)
            if (result.isSuccess) {
                val bytes = modelManager.getTokenizerSizeBytes()
                _uiState.update {
                    it.copy(
                        isTokenizerImported = true,
                        tokenizerSizeBytes = bytes,
                        tokenizerSizeFormatted = modelManager.formatFileSize(bytes),
                        userMessage = "Tokenizer imported successfully!"
                    )
                }
                loadTokenizerInternal()
            } else {
                _uiState.update {
                    it.copy(
                        userMessage = "Failed to import tokenizer: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    fun removeModel() {
        onnxEngine.release()
        modelManager.deleteModel()
        _uiState.update {
            it.copy(
                isModelImported = false,
                isModelLoaded = false,
                modelSizeBytes = 0L,
                modelSizeFormatted = "0 MB",
                userMessage = "Model removed."
            )
        }
    }

    fun removeTokenizer() {
        tokenizer = null
        modelManager.deleteTokenizer()
        _uiState.update {
            it.copy(
                isTokenizerImported = false,
                isTokenizerLoaded = false,
                tokenizerSizeBytes = 0L,
                tokenizerSizeFormatted = "0 KB",
                tokenizerVocabSize = 0,
                userMessage = "Tokenizer removed."
            )
        }
    }

    private fun setupAudioListener() {
        audioRecorder.setListener(object : AudioRecorderManager.AudioListener {
            override fun onAudioChunkAvailable(audioWindow: ShortArray, totalDurationMs: Long) {
                processAudioWindow(audioWindow, totalDurationMs)
            }

            override fun onAmplitudeChanged(rmsNormalized: Float) {
                _uiState.update { it.copy(rmsLevel = rmsNormalized) }
            }

            override fun onError(message: String) {
                _uiState.update { it.copy(userMessage = message, isRecording = false) }
            }
        })
    }

    fun startRecording(): Boolean {
        if (!_uiState.value.isBothReady) {
            _uiState.update { it.copy(userMessage = "Please import both Model and Tokenizer first.") }
            return false
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (!onnxEngine.isLoaded) {
                loadModelInternal()
            }
            if (tokenizer == null) {
                loadTokenizerInternal()
            }

            val started = audioRecorder.startRecording(viewModelScope)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isRecording = started) }
            }
        }
        return true
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
        _uiState.update { current ->
            val finalLive = current.liveTranscript.trim()
            val newFull = if (finalLive.isNotEmpty()) {
                if (current.fullTranscript.isEmpty()) finalLive
                else "${current.fullTranscript}\n$finalLive"
            } else {
                current.fullTranscript
            }
            current.copy(
                isRecording = false,
                rmsLevel = 0f,
                liveTranscript = "",
                fullTranscript = newFull
            )
        }
        lastDecodedText = ""
    }

    fun clearTranscript() {
        committedHistory.clear()
        lastDecodedText = ""
        _uiState.update {
            it.copy(
                liveTranscript = "",
                fullTranscript = ""
            )
        }
    }

    private fun processAudioWindow(audioWindow: ShortArray, durationMs: Long) {
        // Conflated execution: If previous inference is still running, skip this chunk
        // to prevent queuing, memory buildup, and ensure RTF < 1.0
        if (!isInferring.compareAndSet(false, true)) {
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val totalStart = System.currentTimeMillis()

            try {
                // 1. NeMo Mel Preprocessing
                val prepStart = System.currentTimeMillis()
                val preprocessResult = preprocessor.process(audioWindow)
                val prepTime = System.currentTimeMillis() - prepStart

                if (preprocessResult.numFrames <= 0) {
                    return@launch
                }

                // 2. ONNX Inference
                val inferResult = onnxEngine.runInference(preprocessResult)
                if (inferResult.isFailure) {
                    val err = inferResult.exceptionOrNull()?.message ?: "Inference error"
                    Log.e(TAG, "Inference failed: $err")
                    return@launch
                }
                val inference = inferResult.getOrThrow()

                // 3. CTC Greedy Decoding
                val ctcStart = System.currentTimeMillis()
                val decodeResult = ctcDecoder.decode(
                    logprobs = inference.logprobs,
                    numFrames = inference.numFrames,
                    numClasses = inference.numClasses,
                    tokenizer = tokenizer
                )
                val ctcTime = System.currentTimeMillis() - ctcStart

                val totalTime = System.currentTimeMillis() - totalStart
                val rtf = if (durationMs > 0) (totalTime.toFloat() / durationMs.toFloat()) else 0f

                val decodedText = decodeResult.text

                _uiState.update { current ->
                    current.copy(
                        liveTranscript = decodedText,
                        audioWindowMs = durationMs,
                        featureShape = "[1, 80, ${preprocessResult.numFrames}]",
                        preprocessTimeMs = prepTime,
                        inferenceTimeMs = inference.inferenceTimeMs,
                        ctcTimeMs = ctcTime,
                        totalLatencyMs = totalTime,
                        rtf = (rtf * 1000).toInt() / 1000f
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Pipeline processing exception", e)
            } finally {
                isInferring.set(false)
            }
        }
    }

    fun runDiagnosticTest() {
        _uiState.update {
            it.copy(
                isDiagnosticRunning = true,
                diagnosticLogs = listOf("Starting comprehensive offline STT diagnostic test..."),
                diagnosticSuccess = null,
                showDiagnosticDialog = true
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            var allPass = true

            fun log(msg: String, pass: Boolean = true) {
                val prefix = if (pass) "✅ " else "❌ "
                logs.add(prefix + msg)
                if (!pass) allPass = false
                _uiState.update { it.copy(diagnosticLogs = logs.toList()) }
            }

            try {
                // Step 1: Tokenizer check
                val tokenizerFile = modelManager.tokenizerFile
                if (!tokenizerFile.exists()) {
                    log("Tokenizer file missing: ${tokenizerFile.name}", false)
                } else {
                    log("Tokenizer file located (${modelManager.formatFileSize(tokenizerFile.length())})", true)
                    try {
                        val tok = SentencePieceTokenizer.fromFile(tokenizerFile)
                        tokenizer = tok
                        log("Tokenizer loaded successfully. Vocab size = ${tok.vocabSize} pieces", true)
                    } catch (e: Exception) {
                        log("Failed to parse Tokenizer Protobuf: ${e.message}", false)
                    }
                }

                // Step 2: ONNX Model check
                val modelFile = modelManager.modelFile
                if (!modelFile.exists()) {
                    log("ONNX model missing: ${modelFile.name}", false)
                } else {
                    log("ONNX model located (${modelManager.formatFileSize(modelFile.length())})", true)
                    val loadRes = onnxEngine.loadModel(modelFile)
                    if (loadRes.isSuccess) {
                        log("ONNX Session created successfully (CPU Engine)", true)
                    } else {
                        log("Failed to create ONNX session: ${loadRes.exceptionOrNull()?.message}", false)
                    }
                }

                // Step 3 & 4 & 5: Validate signatures
                if (onnxEngine.isLoaded) {
                    val metadata = onnxEngine.validateModelMetadata()
                    log("Inputs found: ${metadata.inputNames}", metadata.inputNames.contains("audio_signal"))
                    log("Outputs found: ${metadata.outputNames}", metadata.outputNames.contains("logprobs"))

                    val audioType = metadata.inputTypes["audio_signal"] ?: "unknown"
                    val lengthType = metadata.inputTypes["length"] ?: "unknown"
                    val logprobsType = metadata.outputTypes["logprobs"] ?: "unknown"

                    log("audio_signal type: $audioType (expected FLOAT)", audioType.contains("FLOAT", ignoreCase = true))
                    log("length type: $lengthType (expected INT64)", lengthType.contains("INT64", ignoreCase = true) || lengthType.contains("INT", ignoreCase = true))
                    log("logprobs type: $logprobsType (expected FLOAT)", logprobsType.contains("FLOAT", ignoreCase = true))

                    log("Input audio_signal shape: ${metadata.inputShapes["audio_signal"]}")
                    log("Input length shape: ${metadata.inputShapes["length"]}")
                    log("Output logprobs shape: ${metadata.outputShapes["logprobs"]}")

                    // Step 6: Synthetic 1-second audio end-to-end dry run
                    log("Executing synthetic 1.0s audio test through DSP -> ONNX -> CTC...")
                    val testAudio = ShortArray(16000) { (kotlin.math.sin(it * 0.1) * 5000).toInt().toShort() }
                    val dspRes = preprocessor.process(testAudio)
                    log("DSP Preprocessor output: [1, 80, ${dspRes.numFrames}] frames")

                    val inferRes = onnxEngine.runInference(dspRes)
                    if (inferRes.isSuccess) {
                        val inf = inferRes.getOrThrow()
                        log("ONNX inference completed in ${inf.inferenceTimeMs} ms, output shape [1, ${inf.numFrames}, ${inf.numClasses}]", true)

                        val ctcRes = ctcDecoder.decode(inf.logprobs, inf.numFrames, inf.numClasses, tokenizer)
                        log("CTC decoding completed. Decoded test tokens: ${ctcRes.tokenIds.size}", true)
                    } else {
                        log("ONNX inference dry run failed: ${inferRes.exceptionOrNull()?.message}", false)
                    }
                }

                log(if (allPass) "ALL DIAGNOSTIC CHECKS PASSED SUCCESSFULLY!" else "DIAGNOSTIC COMPLETED WITH ISSUES", allPass)

            } catch (e: Exception) {
                log("Diagnostic unexpected error: ${e.message}", false)
            } finally {
                _uiState.update {
                    it.copy(
                        isDiagnosticRunning = false,
                        diagnosticSuccess = allPass
                    )
                }
            }
        }
    }

    fun dismissDiagnosticDialog() {
        _uiState.update { it.copy(showDiagnosticDialog = false) }
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun showUserMessage(message: String) {
        _uiState.update { it.copy(userMessage = message) }
    }

    fun setBlankIndex(index: Int) {
        ctcDecoder.blankIndex = index
        _uiState.update { it.copy(blankIndex = index) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        onnxEngine.release()
    }
}
