package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Диагностика AES-CTR: поведение update() с разными размерами чанков. */
class AesCtrDebugTest {

    @Test
    fun aesCtrChunkedMatchesWhole() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val iv = ByteArray(16) { (7 * it).toByte() }
        val data = ByteArray(1500) { ((it * 3) % 256).toByte() }

        // эталон: один update на весь буфер
        val whole = AesCtr(key, iv).update(data)

        // порционно, как в тестах векторов
        val aes = AesCtr(key, iv)
        val out = StringBuilder()
        var acc = ByteArray(0)
        val sizes = listOf(17, 1, 5, 256, 1024, 197)
        var pos = 0
        for (s in sizes) {
            val take = minOf(s, data.size - pos)
            if (take <= 0) break
            val chunk = data.copyOfRange(pos, pos + take)
            val got = aes.update(chunk)
            out.append("chunk=$take got=${got.size} ")
            acc += got
            pos += take
        }
        assertEquals("sizes: $out", data.size, acc.size)
        assertArrayEquals(whole, acc)
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = copyOf(size + other.size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }
}
