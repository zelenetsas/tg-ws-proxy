package com.flowseal.tgwsproxy.proxy

import java.security.MessageDigest

/**
 * Обёртка над AES-CTR (javax.crypto) — эквивалент
 * `Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor().update(data)`.
 *
 * CTR симметричен: «шифрование» и «расшифрование» — это один и тот же XOR
 * с keystream, поэтому используется только ENCRYPT_MODE (как и в Python-версии,
 * где везде вызывается `.encryptor()`).
 *
 * Состояние счётчика сохраняется между вызовами [update], как в Python.
 * Экземпляр НЕ потокобезопасен: каждый поток моста держит свой.
 */
class AesCtr(key: ByteArray, iv: ByteArray) {

    private val cipher: javax.crypto.Cipher =
        javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
        }

    fun update(data: ByteArray): ByteArray = cipher.update(data)

    fun update(data: ByteArray, offset: Int, length: Int): ByteArray =
        cipher.update(data, offset, length)

    /** Прокрутить keystream на [n] байт вперёд, выбросив результат. */
    fun skip(n: Int) {
        cipher.update(ByteArray(n))
    }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

fun ByteArray.toHexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v shr 4].uppercaseChar()).append(HEX[v and 0x0F].uppercaseChar())
    }
    return sb.toString()
}

fun hexByte(b: Byte): String {
    val v = b.toInt() and 0xFF
    return "" + HEX[v shr 4] + HEX[v and 0x0F]
}

fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
    }
    return out
}

fun ByteArray.readUint16Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

fun ByteArray.readUint32Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

fun ByteArray.readUint32Be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

fun ByteArray.readUint16Be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

/** little-endian signed 16-bit — порт `struct.unpack('<h')`. */
fun ByteArray.readInt16Le(offset: Int): Int {
    val v = readUint16Le(offset)
    return if (v and 0x8000 != 0) v - 0x10000 else v
}

fun int16Le(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

/** Порт `x[::-1]` для 48-байтного prekey+iv. */
fun ByteArray.reversedBytes(): ByteArray {
    val out = ByteArray(size)
    for (i in indices) out[i] = this[size - 1 - i]
    return out
}

/** Secure random bytes (порт `os.urandom`). */
fun urandom(n: Int): ByteArray {
    val b = ByteArray(n)
    java.security.SecureRandom().nextBytes(b)
    return b
}

