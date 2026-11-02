package com.flowseal.tgwsproxy.proxy

import java.util.Locale
import kotlin.math.abs

/**
 * Константы и утилиты протокола — порт `proxy/utils.py`.
 */
object Protocol {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD.toInt()

    val ZERO_64 = ByteArray(64)

    /** Первый байт не должен быть 0xEF (иначе коллизия с abridged tag). */
    val RESERVED_FIRST_BYTES: Set<Int> = setOf(0xEF)

    /**
     * Зарезервированные 4-байтовые начала (порт RESERVED_STARTS).
     * Хранятся как hex-строки: value class над ByteArray сравнивал бы ссылки.
     */
    private val RESERVED_STARTS: Set<String> = setOf(
        "48454144", // HEAD
        "504f5354", // POST
        "47455420", // "GET "
        "eeeeeeee",
        "dddddddd",
        "16030102",
        "4f505449", // OPTI
    )

    /** true, если первые 4 байта совпадают с зарезервированным началом. */
    fun isReservedStart(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val sb = StringBuilder(8)
        for (i in 0 until 4) sb.append(hexByte(data[i]))
        return RESERVED_STARTS.contains(sb.toString())
    }

    val RESERVED_CONTINUE = byteArrayOf(0, 0, 0, 0)

    /** DC -> IP по умолчанию (production). */
    val DC_DEFAULT_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    val DC_TEST_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.10",
        2 to "149.154.167.40",
        3 to "149.154.175.117",
    )

    const val WS_PATH = "/apiws"
    const val WS_PATH_TEST = WS_PATH + "_test"

    /** Домены Telegram WS для DC (порт `ws_domains`). */
    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val d = if (dc == 203) 2 else dc
        return if (!isMedia) {
            listOf("kws$d.web.telegram.org")
        } else {
            listOf("kws$d-1.web.telegram.org", "kws$d.web.telegram.org")
        }
    }

    /** Порт `human_bytes` (десятичная точка, как в Python-версии). */
    fun humanBytes(n: Long): String {
        var value = n.toDouble()
        for (unit in arrayOf("B", "KB", "MB", "GB")) {
            if (abs(value) < 1024.0) return String.format(Locale.US, "%.1f%s", value, unit)
            value /= 1024.0
        }
        return String.format(Locale.US, "%.1fTB", value)
    }

    /**
     * Порт `get_link_host`: для 0.0.0.0 определяем исходящий адрес.
     * На Android всегда отдаем хост как есть, если это не wildcard.
     */
    fun getLinkHost(host: String): String {
        if (host != "0.0.0.0") return host
        val s = java.net.DatagramSocket()
        return try {
            s.connect(java.net.InetAddress.getByName("8.8.8.8"), 80)
            s.localAddress?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Ссылка подключения tg://proxy (dd-секрет). */
    fun buildDdLink(host: String, port: Int, secret: String): String =
        "tg://proxy?server=${getLinkHost(host)}&port=$port&secret=dd$secret"

    /** Ссылка подключения tg://proxy (ee-секрет + домен Fake TLS). */
    fun buildEeLink(host: String, port: Int, secret: String, domain: String): String =
        "tg://proxy?server=${getLinkHost(host)}&port=$port" +
            "&secret=ee$secret${domain.toByteArray(Charsets.US_ASCII).toHexLower()}"
}

/**
 * Парсер списка "DC:IP" — порт `parse_dc_ip_list`.
 * Бросает [DcParseError] с тем же kind, что Python ("format" / "invalid").
 */
class DcParseError(val entry: String?, val kind: String, message: String) : Exception(message)

fun parseDcIpList(entries: List<String>): Map<Int, String> {
    val result = LinkedHashMap<Int, String>()
    for (entry in entries) {
        val idx = entry.indexOf(':')
        if (idx < 0) {
            throw DcParseError(entry, "format", "Invalid --dc-ip format '$entry', expected DC:IP")
        }
        val dcS = entry.substring(0, idx)
        val ipS = entry.substring(idx + 1)
        val dcN = dcS.toIntOrNull() ?: throw DcParseError(entry, "invalid", "Invalid --dc-ip '$entry'")
        if (!isValidIpv4(ipS)) throw DcParseError(entry, "invalid", "Invalid --dc-ip '$entry'")
        result[dcN] = ipS
    }
    return result
}

/** Строгая проверка IPv4 в текстовом виде — аналог `socket.inet_pton(AF_INET, …)`. */
fun isValidIpv4(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    for (part in parts) {
        if (part.isEmpty() || part.length > 3) return false
        if (!part.all { it.isDigit() }) return false
        val v = part.toIntOrNull() ?: return false
        if (v > 255) return false
    }
    return true
}

/** Порт `coerce_domain_list`: строка/список -> дедуплицированный список доменов. */
fun coerceDomainList(value: Any?): List<String> {
    val items: List<String> = when (value) {
        is String -> value.replace(',', ' ').replace(';', ' ').split(' ')
        is Iterable<*> -> value.filterIsInstance<String>()
            .flatMap { it.replace(',', ' ').replace(';', ' ').split(' ') }
        else -> return emptyList()
    }
    val seen = HashSet<String>()
    val result = ArrayList<String>()
    for (raw in items) {
        val item = raw.trim()
        if (item.isEmpty()) continue
        if (seen.add(item.lowercase())) result.add(item)
    }
    return result
}
