package com.example.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat

/**
 * Handles persistent storage, validation, stream-copying, and status of
 * the ONNX INT8 model and SentencePiece tokenizer files.
 */
class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_DIR_NAME = "models"
        const val MODEL_FILE_NAME = "kazalbrur_int8.onnx"
        const val TOKENIZER_FILE_NAME = "tokenizer.model"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer
    }

    data class ImportProgress(
        val isImporting: Boolean = false,
        val fileName: String = "",
        val bytesCopied: Long = 0L,
        val totalBytes: Long = -1L,
        val progressFraction: Float = 0f,
        val statusMessage: String = ""
    )

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress = _importProgress.asStateFlow()

    val modelsDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    val modelFile: File
        get() = File(modelsDir, MODEL_FILE_NAME)

    val tokenizerFile: File
        get() = File(modelsDir, TOKENIZER_FILE_NAME)

    fun isModelReady(): Boolean {
        val file = modelFile
        return file.exists() && file.length() > 1_000_000 // At least ~1MB to prevent zero-byte files
    }

    fun isTokenizerReady(): Boolean {
        val file = tokenizerFile
        return file.exists() && file.length() > 100
    }

    fun getModelSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else 0L

    fun getTokenizerSizeBytes(): Long = if (tokenizerFile.exists()) tokenizerFile.length() else 0L

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val df = DecimalFormat("#.##")
        return when {
            bytes >= 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024.0 * 1024.0))} MB"
            bytes >= 1024 -> "${df.format(bytes.toDouble() / 1024.0)} KB"
            else -> "$bytes B"
        }
    }

    /**
     * Stream-copies an imported model file from SAF URI into internal storage.
     * Uses buffered streaming to avoid loading large files into RAM.
     */
    suspend fun importModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        copyUriToFile(
            uri = uri,
            targetFile = modelFile,
            displayName = MODEL_FILE_NAME
        )
    }

    /**
     * Stream-copies an imported tokenizer file from SAF URI into internal storage.
     */
    suspend fun importTokenizerFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        copyUriToFile(
            uri = uri,
            targetFile = tokenizerFile,
            displayName = TOKENIZER_FILE_NAME
        )
    }

    private suspend fun copyUriToFile(
        uri: Uri,
        targetFile: File,
        displayName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            _importProgress.value = ImportProgress(
                isImporting = true,
                fileName = displayName,
                statusMessage = "Opening file stream..."
            )

            val contentResolver = context.contentResolver
            var totalBytes = -1L

            // Attempt to query file size
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        totalBytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {}

            val tempFile = File(modelsDir, "${targetFile.name}.tmp")
            if (tempFile.exists()) tempFile.delete()

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalCopied = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead

                        val fraction = if (totalBytes > 0) {
                            (totalCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                        _importProgress.value = ImportProgress(
                            isImporting = true,
                            fileName = displayName,
                            bytesCopied = totalCopied,
                            totalBytes = totalBytes,
                            progressFraction = fraction,
                            statusMessage = "Copying: ${formatFileSize(totalCopied)}" +
                                    if (totalBytes > 0) " / ${formatFileSize(totalBytes)}" else "..."
                        )
                    }
                    outputStream.flush()
                }
            } ?: return@withContext Result.failure(Exception("Could not open input stream for URI: $uri"))

            // Atomic rename from tmp to destination
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                // Fallback copy if rename fails
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            _importProgress.value = ImportProgress(
                isImporting = false,
                fileName = displayName,
                statusMessage = "Successfully imported $displayName"
            )

            Result.success(targetFile)
        } catch (e: Exception) {
            _importProgress.value = ImportProgress(
                isImporting = false,
                fileName = displayName,
                statusMessage = "Failed: ${e.localizedMessage}"
            )
            Result.failure(e)
        }
    }

    fun deleteModel(): Boolean {
        return if (modelFile.exists()) modelFile.delete() else true
    }

    fun deleteTokenizer(): Boolean {
        return if (tokenizerFile.exists()) tokenizerFile.delete() else true
    }
}
