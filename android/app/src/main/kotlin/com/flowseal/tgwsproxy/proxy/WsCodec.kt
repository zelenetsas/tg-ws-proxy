package com.flowseal.tgwsproxy.proxy

import java.io.EOFException
import java.io.InputStream

/** Порт `proxy/raw_websocket.py::WsHandshakeError`. */
class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
) : Exception("HTTP $statusCode: $statusLine") {

    /** Порт свойства `is_redirect`. */
    val isRedirect: Boolean get() = statusCode in REDIRECT_CODES

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Кодек кадров WebSocket — порт `_xor_mask`, `_build_frame`, `_read_frame`,
 * `_parse_close`. Вынесен отдельно, чтобы кодирование можно было сверить с
 * эталонными векторами без живого сокета.
 */
object WsCodec {
    const val OP_CONT = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    const val MAX_MESSAGE_LEN = 16 * 1024 * 1024

    /** Порт `_xor_mask`: повторяющаяся 4-байтная маска (байт-в-байт как в Python). */
    fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return out
    }

    /**
     * Порт `_build_frame`. [maskKey] принимается только для тестов; в рабочем
     * коде вызывается без него и берётся случайная маска.
     */
    fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean, maskKey: ByteArray? = null): ByteArray {
        val length = data.size
        val fb = 0x80 or opcode
        val headerSize = if (!mask) {
            when {
                length < 126 -> 2
                length < 65536 -> 4
                else -> 10
            }
        } else {
            when {
                length < 126 -> 6
                length < 65536 -> 8
                else -> 14
            }
        }

        val out = ByteArray(headerSize + length)
        var p = 0
        out[p++] = fb.toByte()

        if (!mask) {
            when {
                length < 126 -> out[p++] = length.toByte()
                length < 65536 -> {
                    out[p++] = 126.toByte()
                    out[p++] = ((length shr 8) and 0xFF).toByte()
                    out[p++] = (length and 0xFF).toByte()
                }
                else -> {
                    out[p++] = 127.toByte()
                    for (shift in 56 downTo 0 step 8) {
                        out[p++] = ((length.toLong() shr shift) and 0xFF).toByte()
                    }
                }
            }
            System.arraycopy(data, 0, out, p, length)
            return out
        }

        val key = maskKey ?: urandom(4)
        when {
            length < 126 -> out[p++] = (0x80 or length).toByte()
            length < 65536 -> {
                out[p++] = (0x80 or 126).toByte()
                out[p++] = ((length shr 8) and 0xFF).toByte()
                out[p++] = (length and 0xFF).toByte()
            }
            else -> {
                out[p++] = (0x80 or 127).toByte()
                for (shift in 56 downTo 0 step 8) {
                    out[p++] = ((length.toLong() shr shift) and 0xFF).toByte()
                }
            }
        }
        System.arraycopy(key, 0, out, p, 4)
        p += 4
        val masked = xorMask(data, key)
        System.arraycopy(masked, 0, out, p, length)
        return out
    }


    /** Разобранный кадр WebSocket. */
    data class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Frame && opcode == other.opcode && fin == other.fin &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (opcode * 31 + (if (fin) 1 else 0)) * 31 + payload.contentHashCode()
    }

    /** Порт `_read_frame`: читает ровно один кадр из потока. */
    fun readFrame(input: InputStream): Frame {
        val hdr = readExactly(input, 2)
        val fin = hdr[0].toInt() and 0x80 != 0
        val opcode = hdr[0].toInt() and 0x0F
        var lengthLong: Long = (hdr[1].toInt() and 0x7F).toLong()
        when (lengthLong.toInt()) {
            126 -> lengthLong = readExactly(input, 2).readUint16Be(0).toLong()
            127 -> {
                val b = readExactly(input, 8)
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
                lengthLong = v
            }
        }
        if (lengthLong > MAX_MESSAGE_LEN) {
            throw EOFException("WS frame too large: $lengthLong bytes")
        }
        val length = lengthLong.toInt()
        return if (hdr[1].toInt() and 0x80 != 0) {
            val maskKey = readExactly(input, 4)
            Frame(opcode, xorMask(readExactly(input, length), maskKey), fin)
        } else {
            Frame(opcode, readExactly(input, length), fin)
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw EOFException("expected $n bytes, got $got")
            got += r
        }
        return out
    }

    /** Порт `_parse_close`. */
    fun parseClose(payload: ByteArray?): Pair<Int?, String> {
        if (payload == null || payload.size < 2) return null to ""
        return try {
            val code = payload.readUint16Be(0)
            val text = String(payload, 2, payload.size - 2, Charsets.UTF_8)
            val name = CLOSE_REASONS[code]
            code to (if (name != null) "$text ($name)" else text)
        } catch (e: Exception) {
            null to ""
        }
    }

    private val CLOSE_REASONS = mapOf(
        1000 to "normal", 1001 to "going_away", 1002 to "protocol_error",
        1003 to "unsupported_data", 1006 to "abnormal", 1007 to "bad_data",
        1008 to "policy_violation", 1009 to "too_big", 1010 to "missing_extension",
        1011 to "internal_error",
    )
}
