package com.flowseal.tgwsproxy.proxy

/**
 * Порт `proxy/bridge.py::MsgSplitter`.
 *
 * Режет TCP-поток MTProto на отдельные транспортные пакеты, чтобы каждый
 * можно было отправить своим WebSocket-фреймом. Работает парами
 * (cipher_chunk, plain_chunk): длина берётся из открытого текста, а в
 * границы режутся уже зашифрованные байты.
 */

/** Растущий байтовый буфер с конвейерной семантикой (порт bytearray + del [:n]). */
class ByteBuf(initialCapacity: Int = 8192) {
    private var data = ByteArray(initialCapacity)
    private var filled = 0

    val size: Int get() = filled

    fun extend(src: ByteArray) {
        if (src.isEmpty()) return
        if (filled + src.size > data.size) {
            var cap = data.size
            while (cap < filled + src.size) cap *= 2
            data = data.copyOf(cap)
        }
        System.arraycopy(src, 0, data, filled, src.size)
        filled += src.size
    }

    operator fun get(index: Int): Byte {
        if (index < 0 || index >= filled) throw IndexOutOfBoundsException("$index !< $filled")
        return data[index]
    }

    fun slice(from: Int, to: Int): ByteArray {
        require(from >= 0 && to <= filled && from <= to) { "bad slice $from..$to of $filled" }
        return data.copyOfRange(from, to)
    }

    fun snapshot(): ByteArray = slice(0, filled)

    /** Отбросить первые [n] байт (порт `del buf[:n]`). */
    fun consume(n: Int) {
        require(n in 0..filled)
        if (n == filled) {
            filled = 0
            return
        }
        System.arraycopy(data, n, data, 0, filled - n)
        filled -= n
    }

    fun clear() {
        filled = 0
    }

    fun uint8At(offset: Int): Int = get(offset).toInt() and 0xFF

    /** 3-байтный little-endian (abridged extended header). */
    fun uint24LeAt(offset: Int): Int =
        (uint8At(offset)) or (uint8At(offset + 1) shl 8) or (uint8At(offset + 2) shl 16)

    /** 4-байтный little-endian как беззнаковый. */
    fun uint32LeAt(offset: Int): Long =
        (uint8At(offset).toLong()) or
            (uint8At(offset + 1).toLong() shl 8) or
            (uint8At(offset + 2).toLong() shl 16) or
            (uint8At(offset + 3).toLong() shl 24)
}

class MsgSplitter(private val proto: Int) {

    private val cipherBuf = ByteBuf()
    private val plainBuf = ByteBuf()
    private var disabled = false

    /** Порт `split()`: возвращает список зашифрованных пакетов. */
    fun split(chunk: ByteArray, plain: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf.extend(chunk)
        plainBuf.extend(plain)

        val parts = ArrayList<ByteArray>()
        var offset = 0
        val bufLen = cipherBuf.size
        while (offset < bufLen) {
            val packetLen = nextPacketLen(offset, bufLen - offset) ?: break
            if (packetLen <= 0) {
                parts.add(
                    if (offset == 0) cipherBuf.snapshot()
                    else cipherBuf.slice(offset, bufLen),
                )
                offset = bufLen
                disabled = true
                break
            }
            if (offset == 0 && packetLen == bufLen) {
                parts.add(cipherBuf.snapshot())
            } else {
                parts.add(cipherBuf.slice(offset, offset + packetLen))
            }
            offset += packetLen
        }

        if (offset != 0) {
            cipherBuf.consume(offset)
            plainBuf.consume(offset)
        }
        return parts
    }

    /** Порт `flush()` — остаток буфера одним пакетом. */
    fun flush(): List<ByteArray> {
        if (cipherBuf.size == 0) return emptyList()
        val tail = cipherBuf.snapshot()
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    /** null — данных недостаточно; 0 — битый поток (отключить нарезку). */
    private fun nextPacketLen(offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (proto) {
            Protocol.PROTO_ABRIDGED_INT -> nextAbridgedLen(offset, avail)
            Protocol.PROTO_INTERMEDIATE_INT,
            Protocol.PROTO_PADDED_INTERMEDIATE_INT,
            -> nextIntermediateLen(offset, avail)
            else -> 0
        }
    }

    private fun nextAbridgedLen(offset: Int, avail: Int): Int? {
        val first = plainBuf.uint8At(offset)
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            payloadLen = plainBuf.uint24LeAt(offset + 1) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val payloadLen = (plainBuf.uint32LeAt(offset) and 0x7FFFFFFFL).toInt()
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}
