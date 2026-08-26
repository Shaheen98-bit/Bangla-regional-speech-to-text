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
import com.example.engine.AudioFileProcessor
import com.example.engine.CtcDecoder
import com.example.engine.OnnxAsrEngine
import com.example.engine.TranscriptAccumulator
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

    // Unified Transcript Accumulators (Single Source of Truth)
    val transcriptAccumulator = TranscriptAccumulator()
    val fileAccumulator = TranscriptAccumulator()

    // Backward-compatible alias
    val accumulator: TranscriptAccumulator get() = transcriptAccumulator

    // Audio File Processor using whole-stream VAD & unified TranscriptAccumulator
    private val audioFileProcessor = AudioFileProcessor(preprocessor, onnxEngine, ctcDecoder)

    private val audioRecorder = AudioRecorderManager(
        sampleRate = 16000,
        maxUtteranceDurationMs = 12000,
        stepDurationMs = 400
    )

    private val _uiState = MutableStateFlow(SttUiState())
    val uiState = _uiState.asStateFlow()

    private val isInferring = AtomicBoolean(false)
    private val chunkCounter = AtomicInteger(0)
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
            override fun onAudioChunkAvailable(
                audioWindow: ShortArray,
                totalDurationMs: Long,
                audioFrameStart: Long,
                audioFrameEnd: Long,
                vadState: String,
                isEndOfUtterance: Boolean
            ) {
                processAudioWindow(
                    audioWindow = audioWindow,
                    durationMs = totalDurationMs,
                    audioFrameStart = audioFrameStart,
                    audioFrameEnd = audioFrameEnd,
                    vadState = vadState,
                    isEndOfUtterance = isEndOfUtterance
                )
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
            val started = audioRecorder.startRecording(viewModelScope)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRecording = started,
                        liveTranscript = "",
                        vadState = AudioRecorderManager.VAD_SILENCE
                    )
                }
            }
        }
        return true
    }

    fun stopRecording() {
        // 1. Stop audio capture
        audioRecorder.stopRecording()

        // 2. Commit any remaining live partial text exactly once
        val flushedSegment = transcriptAccumulator.flushOnStop()

        if (flushedSegment != null) {
            Log.i(TAG, """
--- ASR PIPELINE LOG ---
audioFrameStart: ${_uiState.value.audioFrameStart}
audioFrameEnd: ${_uiState.value.audioFrameEnd}
VAD state: RECORDING_STOP_FLUSH
decoded text: "${flushedSegment.text}"
stable prefix: ""
committed text: "${flushedSegment.text}"
finalTranscript length: ${transcriptAccumulator.finalTranscript.length}
------------------------
            """.trimIndent())
        }

        // 3. Update UI state without ever clearing finalTranscript
        _uiState.update { current ->
            current.copy(
                isRecording = false,
                rmsLevel = 0f,
                finalizedSegments = transcriptAccumulator.finalizedSegments,
                finalTranscript = transcriptAccumulator.finalTranscript,
                liveTranscript = "",
                stablePrefix = "",
                lastCommittedText = flushedSegment?.text ?: current.lastCommittedText,
                vadState = "STOPPED"
            )
        }
    }

    /**
     * User explicitly pressed Clear button.
     */
    fun clearTranscript() {
        transcriptAccumulator.clear()
        _uiState.update {
            it.copy(
                finalizedSegments = emptyList(),
                finalTranscript = "",
                liveTranscript = "",
                stablePrefix = "",
                lastCommittedText = ""
            )
        }
    }

    /**
     * User explicitly pressed Clear in audio file tab.
     */
    fun clearFileTranscript() {
        fileAccumulator.clear()
        _uiState.update {
            it.copy(
                fileFinalizedSegments = emptyList(),
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

            fileAccumulator.clear()

            _uiState.update {
                it.copy(
                    isTranscribingFile = true,
                    selectedAudioFileName = fileName,
                    fileTranscriptionProgress = 0.05f,
                    fileTranscriptionStatus = "অডিও ফাইল ডিকোড করা হচ্ছে...",
                    fileFinalizedSegments = emptyList(),
                    fileTranscript = "",
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
                        fileTranscriptionProgress = 0.52f,
                        fileTranscriptionStatus = "অডিও সেগমেন্টেশন ও অন-ডিভাইস STT চলছে..."
                    )
                }

                // 2. Transcribe using AudioFileProcessor with unified TranscriptAccumulator
                val finalizedSegments = audioFileProcessor.transcribeAudio(
                    samples = decodedAudio.samples,
                    tokenizer = tokenizer,
                    accumulator = fileAccumulator
                ) { progress, newSeg, fullText ->
                    _uiState.update {
                        it.copy(
                            fileTranscriptionProgress = progress,
                            fileTranscript = fullText,
                            fileFinalizedSegments = fileAccumulator.finalizedSegments,
                            fileTranscriptionStatus = "প্রসেসিং হচ্ছে... (${(progress * 100).toInt()}%)"
                        )
                    }
                }

                val finalFullText = fileAccumulator.finalTranscript

                _uiState.update {
                    it.copy(
                        isTranscribingFile = false,
                        fileTranscriptionProgress = 1.0f,
                        fileFinalizedSegments = finalizedSegments,
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

    private fun processAudioWindow(
        audioWindow: ShortArray,
        durationMs: Long,
        audioFrameStart: Long,
        audioFrameEnd: Long,
        vadState: String,
        isEndOfUtterance: Boolean
    ) {
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

                // If chunk is silence, update telemetry and skip ONNX (never clear finalTranscript)
                if (preprocessResult.isSilence) {
                    _uiState.update { current ->
                        current.copy(
                            audioWindowMs = durationMs,
                            audioFrameStart = audioFrameStart,
                            audioFrameEnd = audioFrameEnd,
                            vadState = vadState,
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

                if (isEndOfUtterance) {
                    // Utterance boundary reached: commit only the new/stable sentence once to finalTranscript
                    val committedSegment = transcriptAccumulator.commitFinal(
                        rawText = decodedText,
                        startMs = (audioFrameStart * 1000L) / 16000L,
                        endMs = (audioFrameEnd * 1000L) / 16000L
                    )
                    val committedText = committedSegment?.text ?: ""
                    val stablePrefix = transcriptAccumulator.stablePrefix
                    val fullTranscriptLen = transcriptAccumulator.finalTranscript.length

                    Log.i(TAG, """
--- ASR PIPELINE LOG ---
audioFrameStart: $audioFrameStart
audioFrameEnd: $audioFrameEnd
VAD state: $vadState
decoded text: "$decodedText"
stable prefix: "$stablePrefix"
committed text: "$committedText"
finalTranscript length: $fullTranscriptLen
------------------------
                    """.trimIndent())

                    _uiState.update { current ->
                        current.copy(
                            finalizedSegments = transcriptAccumulator.finalizedSegments,
                            finalTranscript = transcriptAccumulator.finalTranscript,
                            liveTranscript = "",
                            stablePrefix = "",
                            lastCommittedText = committedText.ifEmpty { current.lastCommittedText },
                            audioFrameStart = audioFrameStart,
                            audioFrameEnd = audioFrameEnd,
                            vadState = vadState,
                            audioWindowMs = durationMs,
                            featureShape = "[1, 80, ${preprocessResult.numFrames}]",
                            preprocessTimeMs = prepTime,
                            inferenceTimeMs = inference.inferenceTimeMs,
                            ctcTimeMs = ctcTime,
                            totalLatencyMs = totalTime,
                            rtf = (rtf * 1000).toInt() / 1000f
                        )
                    }
                } else {
                    // Intermediate streaming hypothesis: updates liveTranscript ONLY (never overwrites finalTranscript)
                    val currentLive = transcriptAccumulator.updateLive(decodedText)
                    val stablePrefix = transcriptAccumulator.stablePrefix
                    val fullTranscriptLen = transcriptAccumulator.finalTranscript.length

                    Log.d(TAG, """
--- ASR PIPELINE LOG ---
audioFrameStart: $audioFrameStart
audioFrameEnd: $audioFrameEnd
VAD state: $vadState
decoded text: "$decodedText"
stable prefix: "$stablePrefix"
committed text: ""
finalTranscript length: $fullTranscriptLen
------------------------
                    """.trimIndent())

                    _uiState.update { current ->
                        current.copy(
                            finalizedSegments = transcriptAccumulator.finalizedSegments, // Immutable
                            finalTranscript = transcriptAccumulator.finalTranscript, // NEVER erased or replaced
                            liveTranscript = currentLive,
                            stablePrefix = stablePrefix,
                            audioFrameStart = audioFrameStart,
                            audioFrameEnd = audioFrameEnd,
                            vadState = vadState,
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
