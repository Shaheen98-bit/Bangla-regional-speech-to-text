package com.example.tokenizer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin Protobuf parser for SentencePiece ModelProto (tokenizer.model).
 * Zero external dependencies. Reads pieces, scores, and types directly.
 */
object SentencePieceProtoParser {

    data class Piece(
        val piece: String,
        val score: Float,
        val type: Int // 1: NORMAL, 2: UNKNOWN, 3: CONTROL, 4: USER_DEFINED, 5: BYTE, 6: UNUSED
    )

    fun parse(file: File): List<Piece> {
        FileInputStream(file).use { input ->
            return parse(input)
        }
    }

    fun parse(bytes: ByteArray): List<Piece> {
        ByteArrayInputStream(bytes).use { input ->
            return parse(input)
        }
    }

    fun parse(input: InputStream): List<Piece> {
        val pieces = ArrayList<Piece>()
        val data = input.readBytes()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.hasRemaining()) {
            val tag = readVarint32(buffer) ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (fieldNumber == 1 && wireType == 2) {
                // Field 1 in ModelProto is repeated SentencePiece
                val length = readVarint32(buffer) ?: break
                if (buffer.remaining() < length) break
                val slice = buffer.slice()
                slice.limit(length)
                buffer.position(buffer.position() + length)

                val piece = parseSentencePiece(slice)
                pieces.add(piece)
            } else {
                skipField(buffer, wireType)
            }
        }
        return pieces
    }

    private fun parseSentencePiece(buffer: ByteBuffer): Piece {
        var pieceStr = ""
        var scoreVal = 0.0f
        var typeVal = 1

        while (buffer.hasRemaining()) {
            val tag = readVarint32(buffer) ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> { // piece: string
                    if (wireType == 2) {
                        val len = readVarint32(buffer) ?: break
                        if (buffer.remaining() >= len) {
                            val strBytes = ByteArray(len)
                            buffer.get(strBytes)
                            pieceStr = String(strBytes, Charsets.UTF_8)
                        }
                    } else {
                        skipField(buffer, wireType)
                    }
                }
                2 -> { // score: float (32-bit little-endian)
                    if (wireType == 5 && buffer.remaining() >= 4) {
                        scoreVal = buffer.float
                    } else {
                        skipField(buffer, wireType)
                    }
                }
                3 -> { // type: enum varint
                    if (wireType == 0) {
                        typeVal = readVarint32(buffer) ?: 1
                    } else {
                        skipField(buffer, wireType)
                    }
                }
                else -> {
                    skipField(buffer, wireType)
                }
            }
        }

        return Piece(pieceStr, scoreVal, typeVal)
    }

    private fun readVarint32(buffer: ByteBuffer): Int? {
        var result = 0
        var shift = 0
        while (buffer.hasRemaining()) {
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) {
                return result
            }
            shift += 7
            if (shift >= 35) {
                return result
            }
        }
        return null
    }

    private fun skipField(buffer: ByteBuffer, wireType: Int) {
        when (wireType) {
            0 -> { // Varint
                readVarint32(buffer)
            }
            1 -> { // 64-bit
                if (buffer.remaining() >= 8) buffer.position(buffer.position() + 8)
            }
            2 -> { // Length-delimited
                val len = readVarint32(buffer) ?: return
                if (buffer.remaining() >= len) buffer.position(buffer.position() + len)
            }
            5 -> { // 32-bit
                if (buffer.remaining() >= 4) buffer.position(buffer.position() + 4)
            }
        }
    }
}
