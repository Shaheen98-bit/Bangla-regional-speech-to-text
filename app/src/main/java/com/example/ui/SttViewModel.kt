package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioFileDecoder
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
import java.util.concurrent.atomic.AtomicInteger

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
        maxUtteranceDurationMs = 12000,
        stepDurationMs = 400
    )

    private val _uiState = MutableStateFlow(SttUiState())
    val uiState = _uiState.asStateFlow()

    private val isInferring = AtomicBoolean(false)
    private val chunkCounter = AtomicInteger(0)
    private var lastCommittedSentence: String = ""
    private var fileTranscriptionJob: Job? = null

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
                Log.i(TAG, "Tokenizer loaded successfully. Vocab size: ${tok.vocabSize}, Blank index: ${ctcDecoder.blankIndex}")
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
                    Log.i(TAG, "ONNX model loaded successfully from ${file.absolutePath}")
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
            override fun onAudioChunkAvailable(audioWindow: ShortArray, totalDurationMs: Long, isEndOfUtterance: Boolean) {
                processAudioWindow(audioWindow, totalDurationMs, isEndOfUtterance)
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

            chunkCounter.set(0)
            lastCommittedSentence = ""
            val started = audioRecorder.startRecording(viewModelScope)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isRecording = started, liveTranscript = "") }
            }
        }
        return true
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
        _uiState.update { current ->
            val finalLive = current.liveTranscript.trim()
            val newFull = if (finalLive.isNotEmpty() && finalLive != lastCommittedSentence) {
                lastCommittedSentence = finalLive
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
    }

    fun clearTranscript() {
        lastCommittedSentence = ""
        _uiState.update {
            it.copy(
                liveTranscript = "",
                fullTranscript = ""
            )
        }
    }

    fun clearFileTranscript() {
        _uiState.update {
            it.copy(
                fileTranscript = "",
                selectedAudioFileName = "",
                fileTranscriptionStatus = ""
            )
        }
    }

    fun toggleConfigCollapsed() {
        _uiState.update { it.copy(isConfigCollapsed = !it.isConfigCollapsed) }
    }

    fun setSelectedTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun transcribeAudioFile(uri: Uri) {
        if (!_uiState.value.isBothReady) {
            _uiState.update { it.copy(userMessage = "Please import both Model and Tokenizer first.") }
            return
        }

        fileTranscriptionJob?.cancel()
        fileTranscriptionJob = viewModelScope.launch(Dispatchers.IO) {
            val appContext = getApplication<Application>().applicationContext
            var fileName = "audio_file"

            try {
                appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: "audio_file"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve audio file name", e)
            }

            _uiState.update {
                it.copy(
                    isTranscribingFile = true,
                    selectedAudioFileName = fileName,
                    fileTranscriptionProgress = 0.05f,
                    fileTranscriptionStatus = "অডিও ফাইল ডিকোড করা হচ্ছে...",
                    userMessage = null
                )
            }

            if (!onnxEngine.isLoaded) {
                loadModelInternal()
            }
            if (tokenizer == null) {
                loadTokenizerInternal()
            }

            try {
                // 1. Decode Audio to 16 kHz Mono PCM
                val decodedAudio = AudioFileDecoder.decodeAudioUri(appContext, uri) { progress ->
                    _uiState.update {
                        it.copy(
                            fileTranscriptionProgress = progress,
                            fileTranscriptionStatus = "ডিকোড হচ্ছে... (${(progress * 100).toInt()}%)"
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        fileTranscriptionProgress = 0.55f,
                        fileTranscriptionStatus = "মেল স্পেকট্রোগ্রাম ও অন-ডিভাইস STT চালানো হচ্ছে..."
                    )
                }

                // 2. Transcribe in chunks (e.g. 5-8 seconds each) to avoid OOM and provide incremental progress
                val samples = decodedAudio.samples
                val sampleRate = 16000
                val chunkSamples = sampleRate * 6 // 6s per chunk
                val stepSamples = sampleRate * 5  // 1s overlap
                val accumulatedSentences = mutableListOf<String>()

                var offset = 0
                val totalSamples = samples.size

                while (offset < totalSamples) {
                    val end = (offset + chunkSamples).coerceAtMost(totalSamples)
                    val chunk = samples.copyOfRange(offset, end)

                    val prepRes = preprocessor.process(chunk)
                    if (prepRes.numFrames > 0 && !prepRes.isSilence) {
                        val inferRes = onnxEngine.runInference(prepRes)
                        if (inferRes.isSuccess) {
                            val inference = inferRes.getOrThrow()
                            val decodeRes = ctcDecoder.decode(
                                logprobs = inference.logprobs,
                                numFrames = inference.numFrames,
                                numClasses = inference.numClasses,
                                tokenizer = tokenizer
                            )
                            val cleanText = decodeRes.text.trim()
                            if (cleanText.isNotEmpty()) {
                                // Avoid duplicate consecutive lines
                                if (accumulatedSentences.isEmpty() || accumulatedSentences.last() != cleanText) {
                                    accumulatedSentences.add(cleanText)
                                }
                            }
                        }
                    }

                    offset += stepSamples
                    val currentProgress = 0.55f + ((offset.toFloat() / totalSamples.toFloat()) * 0.45f).coerceAtMost(0.42f)
                    val currentText = accumulatedSentences.joinToString("\n")

                    _uiState.update {
                        it.copy(
                            fileTranscriptionProgress = currentProgress.coerceIn(0f, 0.98f),
                            fileTranscript = currentText,
                            fileTranscriptionStatus = "প্রসেসিং হচ্ছে... (${(currentProgress * 100).toInt()}%)"
                        )
                    }
                }

                val finalFullText = accumulatedSentences.joinToString("\n")

                _uiState.update {
                    it.copy(
                        isTranscribingFile = false,
                        fileTranscriptionProgress = 1.0f,
                        fileTranscript = if (finalFullText.isNotEmpty()) finalFullText else "কোনো স্পষ্ট বাংলা কথা শনাক্ত করা যায়নি।",
                        fileTranscriptionStatus = "ট্রান্সক্রিপশন সম্পন্ন (${(decodedAudio.durationMs / 1000)}s)"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Audio file transcription failed", e)
                _uiState.update {
                    it.copy(
                        isTranscribingFile = false,
                        fileTranscriptionStatus = "ব্যর্থ হয়েছে: ${e.localizedMessage}",
                        userMessage = "অডিও ট্রান্সক্রিপশন ব্যর্থ: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun cancelFileTranscription() {
        fileTranscriptionJob?.cancel()
        fileTranscriptionJob = null
        _uiState.update {
            it.copy(
                isTranscribingFile = false,
                fileTranscriptionStatus = "বাতিল করা হয়েছে"
            )
        }
    }

    private fun processAudioWindow(audioWindow: ShortArray, durationMs: Long, isEndOfUtterance: Boolean) {
        // Conflated execution: prevent queuing if previous inference is still in progress
        if (!isInferring.compareAndSet(false, true)) {
            return
        }

        val currentChunkNum = chunkCounter.incrementAndGet()

        viewModelScope.launch(Dispatchers.Default) {
            val totalStart = System.currentTimeMillis()

            try {
                // 1. NeMo Mel Spectrogram Preprocessing (ONLY raw audio PCM is passed)
                val prepStart = System.currentTimeMillis()
                val preprocessResult = preprocessor.process(audioWindow)
                val prepTime = System.currentTimeMillis() - prepStart

                if (preprocessResult.numFrames <= 0) {
                    return@launch
                }

                // If chunk is pure silence (e.g. background room noise before speech), skip model inference
                if (preprocessResult.isSilence) {
                    _uiState.update { current ->
                        current.copy(
                            audioWindowMs = durationMs,
                            featureShape = "[1, 80, ${preprocessResult.numFrames}]"
                        )
                    }
                    return@launch
                }

                // 2. ONNX Inference (ONLY audio features are fed into ONNX model)
                val inferResult = onnxEngine.runInference(preprocessResult)
                if (inferResult.isFailure) {
                    val err = inferResult.exceptionOrNull()?.message ?: "Inference error"
                    Log.e(TAG, "Inference failed: $err")
                    return@launch
                }
                val inference = inferResult.getOrThrow()

                // 3. CTC Greedy Decoding via SentencePiece Tokenizer
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

                // 4. Detailed STT Debug Logging (Required by specification)
                val rawStr = if (decodeResult.rawArgmax.size > 20) {
                    decodeResult.rawArgmax.take(20).joinToString(", ") + "... (${decodeResult.rawArgmax.size} frames)"
                } else {
                    decodeResult.rawArgmax.joinToString(", ")
                }

                Log.d(TAG, """
--- STT DEBUG ---
chunk number: $currentChunkNum
audio samples: ${audioWindow.size} (${durationMs}ms)
mel shape: [1, ${preprocessResult.nMels}, ${preprocessResult.numFrames}]
length: ${preprocessResult.numFrames}
ONNX output shape: [1, ${inference.numFrames}, ${inference.numClasses}]
argmax IDs: [$rawStr]
blank ID: ${ctcDecoder.blankIndex}
collapsed IDs: ${decodeResult.collapsedIds}
tokenizer pieces: ${decodeResult.pieces}
decoded text: "$decodedText"
---------------
                """.trimIndent())

                // 5. Update UI State
                _uiState.update { current ->
                    if (isEndOfUtterance) {
                        // Sentence completed: append to full transcript and clear live (preventing duplicate appends)
                        val cleanDecoded = decodedText.trim()
                        val updatedFull = if (cleanDecoded.isNotEmpty() && cleanDecoded != lastCommittedSentence) {
                            lastCommittedSentence = cleanDecoded
                            if (current.fullTranscript.isEmpty()) cleanDecoded
                            else "${current.fullTranscript}\n$cleanDecoded"
                        } else {
                            current.fullTranscript
                        }
                        current.copy(
                            liveTranscript = "",
                            fullTranscript = updatedFull,
                            audioWindowMs = durationMs,
                            featureShape = "[1, 80, ${preprocessResult.numFrames}]",
                            preprocessTimeMs = prepTime,
                            inferenceTimeMs = inference.inferenceTimeMs,
                            ctcTimeMs = ctcTime,
                            totalLatencyMs = totalTime,
                            rtf = (rtf * 1000).toInt() / 1000f
                        )
                    } else {
                        // Live updating during speech
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
                        ctcDecoder.updateBlankIndexFromVocab(tok.vocabSize, 129)
                        log("Tokenizer loaded: ${tok.vocabSize} tokens. CTC Blank ID: ${ctcDecoder.blankIndex}", true)
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

                // Step 3: Validate signatures
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

                    // Step 4: Synthetic audio test
                    log("Executing synthetic 1.0s audio test through DSP -> ONNX -> CTC...")
                    val testAudio = ShortArray(16000) { (kotlin.math.sin(it * 0.1) * 5000).toInt().toShort() }
                    val dspRes = preprocessor.process(testAudio)
                    log("DSP Preprocessor output: [1, 80, ${dspRes.numFrames}] frames")

                    val inferRes = onnxEngine.runInference(dspRes)
                    if (inferRes.isSuccess) {
                        val inf = inferRes.getOrThrow()
                        log("ONNX inference completed in ${inf.inferenceTimeMs} ms, output shape [1, ${inf.numFrames}, ${inf.numClasses}]", true)

                        val ctcRes = ctcDecoder.decode(inf.logprobs, inf.numFrames, inf.numClasses, tokenizer)
                        log("CTC decoding completed. Collapsed tokens: ${ctcRes.tokenIds.size}", true)
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
