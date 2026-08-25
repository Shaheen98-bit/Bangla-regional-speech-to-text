package com.example.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.example.dsp.MelSpectrogramPreprocessor
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime engine managing the INT8 Kazalbrur Conformer 120M ASR model session.
 * Primary target: CPU inference on 4GB RAM Android devices.
 * Creates ONE OrtEnvironment and ONE OrtSession.
 */
class OnnxAsrEngine {

    companion object {
        private const val TAG = "OnnxAsrEngine"
        const val INPUT_AUDIO_SIGNAL = "audio_signal"
        const val INPUT_LENGTH = "length"
        const val OUTPUT_LOGPROBS = "logprobs"
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded: Boolean = false

    data class InferenceResult(
        val logprobs: FloatArray,
        val numFrames: Int, // T
        val numClasses: Int, // 129
        val inferenceTimeMs: Long
    )

    data class ModelMetadata(
        val inputNames: List<String>,
        val outputNames: List<String>,
        val inputTypes: Map<String, String>,
        val outputTypes: Map<String, String>,
        val inputShapes: Map<String, String>,
        val outputShapes: Map<String, String>,
        val isSignatureValid: Boolean,
        val validationMessage: String
    )

    val isLoaded: Boolean
        get() = isModelLoaded && ortSession != null

    /**
     * Initializes the ONNX Runtime session with optimized CPU configuration.
     */
    @Synchronized
    fun loadModel(modelFile: File, numThreads: Int = 2): Result<Unit> {
        return try {
            if (isLoaded) {
                return Result.success(Unit)
            }

            if (!modelFile.exists() || modelFile.length() < 1000) {
                return Result.failure(IllegalStateException("Model file does not exist or is corrupted."))
            }

            if (ortEnv == null) {
                ortEnv = OrtEnvironment.getEnvironment("KazalbrurSttEnv")
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            ortSession = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
            isModelLoaded = true
            Log.i(TAG, "ONNX model loaded successfully from ${modelFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
            isModelLoaded = false
            Result.failure(e)
        }
    }

    /**
     * Inspects the loaded ONNX model metadata and validates input/output signatures.
     */
    fun validateModelMetadata(): ModelMetadata {
        val session = ortSession
            ?: return ModelMetadata(
                emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                false, "Model is not loaded."
            )

        val inputNames = session.inputNames.toList()
        val outputNames = session.outputNames.toList()
        val inputTypes = mutableMapOf<String, String>()
        val outputTypes = mutableMapOf<String, String>()
        val inputShapes = mutableMapOf<String, String>()
        val outputShapes = mutableMapOf<String, String>()

        for ((name, nodeInfo) in session.inputInfo) {
            val info = nodeInfo.info
            if (info is TensorInfo) {
                inputTypes[name] = info.type.name
                inputShapes[name] = info.shape.contentToString()
            } else {
                inputTypes[name] = info.toString()
            }
        }

        for ((name, nodeInfo) in session.outputInfo) {
            val info = nodeInfo.info
            if (info is TensorInfo) {
                outputTypes[name] = info.type.name
                outputShapes[name] = info.shape.contentToString()
            } else {
                outputTypes[name] = info.toString()
            }
        }

        val hasAudioSignal = inputNames.contains(INPUT_AUDIO_SIGNAL)
        val hasLength = inputNames.contains(INPUT_LENGTH)
        val hasLogprobs = outputNames.contains(OUTPUT_LOGPROBS)

        val isValid = hasAudioSignal && hasLength && hasLogprobs
        val message = if (isValid) {
            "Model signature verified: inputs [audio_signal, length] -> output [logprobs]"
        } else {
            "Signature mismatch: inputs=${inputNames}, outputs=${outputNames}"
        }

        return ModelMetadata(
            inputNames = inputNames,
            outputNames = outputNames,
            inputTypes = inputTypes,
            outputTypes = outputTypes,
            inputShapes = inputShapes,
            outputShapes = outputShapes,
            isSignatureValid = isValid,
            validationMessage = message
        )
    }

    /**
     * Runs inference on the preprocessed mel features.
     * @param preprocessResult [1, 80, T] mel features
     */
    @Synchronized
    fun runInference(preprocessResult: MelSpectrogramPreprocessor.PreprocessResult): Result<InferenceResult> {
        val session = ortSession
            ?: return Result.failure(IllegalStateException("ONNX Session is not initialized."))
        val env = ortEnv
            ?: return Result.failure(IllegalStateException("ONNX Environment is not initialized."))

        val numFrames = preprocessResult.numFrames
        val nMels = preprocessResult.nMels
        if (numFrames <= 0) {
            return Result.failure(IllegalArgumentException("Audio feature duration too short (0 frames)."))
        }

        val startTime = System.currentTimeMillis()

        var tensorAudioSignal: OnnxTensor? = null
        var tensorLength: OnnxTensor? = null
        var outputResult: OrtSession.Result? = null

        return try {
            // Shape: [1, 80, T]
            val signalShape = longArrayOf(1L, nMels.toLong(), numFrames.toLong())
            val signalBuffer = preprocessResult.toDirectFloatBuffer()
            tensorAudioSignal = OnnxTensor.createTensor(env, signalBuffer, signalShape)

            // Shape: [1] with value T
            val lengthShape = longArrayOf(1L)
            val lengthBuffer = LongBuffer.wrap(longArrayOf(numFrames.toLong()))
            tensorLength = OnnxTensor.createTensor(env, lengthBuffer, lengthShape)

            val inputs = mapOf(
                INPUT_AUDIO_SIGNAL to tensorAudioSignal,
                INPUT_LENGTH to tensorLength
            )

            outputResult = session.run(inputs)

            val logprobsOutput = outputResult.get(OUTPUT_LOGPROBS).orElse(null)
                ?: outputResult.get(0).value // Fallback to first output

            val logprobsTensor = logprobsOutput as? OnnxTensor
                ?: return Result.failure(IllegalStateException("Output is not an OnnxTensor."))

            val outputShape = logprobsTensor.info.shape
            // Expected: [1, T, 129] or similar
            val outFrames = if (outputShape.size >= 2) outputShape[1].toInt() else numFrames
            val outClasses = if (outputShape.size >= 3) outputShape[2].toInt() else 129

            val floatBuffer: FloatBuffer = logprobsTensor.floatBuffer
            val flatLogprobs = FloatArray(floatBuffer.remaining())
            floatBuffer.get(flatLogprobs)

            val inferenceTime = System.currentTimeMillis() - startTime

            Result.success(
                InferenceResult(
                    logprobs = flatLogprobs,
                    numFrames = outFrames,
                    numClasses = outClasses,
                    inferenceTimeMs = inferenceTime
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Inference execution error", e)
            Result.failure(e)
        } finally {
            tensorAudioSignal?.close()
            tensorLength?.close()
            outputResult?.close()
        }
    }

    @Synchronized
    fun release() {
        try {
            ortSession?.close()
            ortSession = null
            ortEnv?.close()
            ortEnv = null
            isModelLoaded = false
            Log.i(TAG, "ONNX engine released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX engine", e)
        }
    }
}
