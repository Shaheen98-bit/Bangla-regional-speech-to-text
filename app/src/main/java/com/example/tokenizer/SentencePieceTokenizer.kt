package com.example.tokenizer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Android-compatible SentencePiece Tokenizer decoder for Bangla text.
 * Loads tokenizer.model binary and decodes token ID sequences.
 */
class SentencePieceTokenizer private constructor(
    private val pieces: List<SentencePieceProtoParser.Piece>
) {
    val vocabSize: Int get() = pieces.size

    val vocabulary: List<String> = pieces.map { it.piece }

    /**
     * Decodes a list of token IDs into a human-readable Bangla Unicode string.
     */
    fun decode(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""

        val rawTextBuilder = StringBuilder()
        val byteBuffer = ByteArrayOutputStream()

        for (id in tokenIds) {
            if (id < 0 || id >= pieces.size) continue

            val pieceObj = pieces[id]
            val piece = pieceObj.piece

            // Skip special control tokens (type 2: UNKNOWN, 3: CONTROL, 6: UNUSED)
            if (pieceObj.type == 3 || piece == "<unk>" || piece == "<s>" || piece == "</s>" || piece == "<pad>" || piece == "<blank>") {
                continue
            }

            // Check for byte fallback e.g. "<0xXX>"
            if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
                try {
                    val hexStr = piece.substring(3, 5)
                    val byteVal = hexStr.toInt(16).toByte()
                    byteBuffer.write(byteArrayOf(byteVal), 0, 1)
                    continue
                } catch (_: Exception) {
                    // Fallthrough to normal handling
                }
            }

            // Flush pending byte buffer before regular text
            if (byteBuffer.size() > 0) {
                val decodedBytesStr = String(byteBuffer.toByteArray(), Charsets.UTF_8)
                rawTextBuilder.append(decodedBytesStr)
                byteBuffer.reset()
            }

            rawTextBuilder.append(piece)
        }

        // Flush any remaining byte buffer
        if (byteBuffer.size() > 0) {
            val decodedBytesStr = String(byteBuffer.toByteArray(), Charsets.UTF_8)
            rawTextBuilder.append(decodedBytesStr)
            byteBuffer.reset()
        }

        val raw = rawTextBuilder.toString()

        // SentencePiece uses ' ' (U+2581) to denote whitespace / word boundaries
        // Replace U+2581 with standard space, and clean up duplicate whitespace
        val withSpaces = raw.replace('\u2581', ' ').replace('_', ' ')
        return withSpaces.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Returns piece for specific token index.
     */
    fun getPiece(id: Int): String? {
        return if (id in 0 until pieces.size) pieces[id].piece else null
    }

    companion object {
        fun fromFile(file: File): SentencePieceTokenizer {
            require(file.exists()) { "Tokenizer file does not exist: ${file.absolutePath}" }
            val pieces = SentencePieceProtoParser.parse(file)
            return SentencePieceTokenizer(pieces)
        }

        fun fromInputStream(inputStream: InputStream): SentencePieceTokenizer {
            val pieces = SentencePieceProtoParser.parse(inputStream)
            return SentencePieceTokenizer(pieces)
        }
    }
}
