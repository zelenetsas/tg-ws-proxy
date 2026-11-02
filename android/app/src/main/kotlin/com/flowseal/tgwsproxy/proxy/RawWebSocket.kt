package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Порт `proxy/raw_websocket.py::RawWebSocket` — минимальный WebSocket-клиент
 * поверх TCP/TLS с полным контролем handshake (SNI, статус-коды 30x),
 * который недоступен готовым WS-библиотекам.
 */
class RawWebSocket private constructor(
    private val conn: StreamConnection,
) {
    @Volatile
    var closed: Boolean = false
        private set

    /** Момент создания в микросекундах (аналог time.monotonic() для возраста в пуле). */
    val createdAtMicros: Long = System.nanoTime() / 1000

    private val frag = ByteArrayOutputStream(8192)

    /** Аналог `_is_stale` из пула. */
    val isStale: Boolean
        get() = closed || conn.isClosing || conn.isAtEof || conn.hasException

    suspend fun send(data: ByteArray) {
        if (closed) throw IOException("WebSocket closed")
        conn.write(WsCodec.buildFrame(WsCodec.OP_BINARY, data, mask = true))
    }

    /**
     * Keepalive PING на idle-соединение: HyperOS/Doze режет «молчащий» трафик,
     * а periodic ping держит NAT/маршрут живым. На PONG апстрима отвечает recv().
     */
    suspend fun ping() {
        if (closed) return
        conn.write(WsCodec.buildFrame(WsCodec.OP_PING, ByteArray(0), mask = true))
    }

    suspend fun sendBatch(parts: List<ByteArray>) {
        if (closed) throw IOException("WebSocket closed")
        conn.writeAll(parts.map { WsCodec.buildFrame(WsCodec.OP_BINARY, it, mask = true) })
    }

    /** Порт `recv()`: null при CLOSE/EOF. */
    suspend fun recv(): ByteArray? = withContext(Dispatchers.IO) {
        while (!closed) {
            val frame = try {
                WsCodec.readFrame(conn.input)
            } catch (e: IOException) {
                closed = true
                return@withContext null
            }
            when (frame.opcode) {
                WsCodec.OP_CLOSE -> {
                    closed = true
                    try {
                        val echo = if (frame.payload.isNotEmpty()) {
                            frame.payload.copyOfRange(0, minOf(2, frame.payload.size))
                        } else {
                            ByteArray(0)
                        }
                        conn.write(WsCodec.buildFrame(WsCodec.OP_CLOSE, echo, mask = true))
                    } catch (_: Exception) {
                    }
                    return@withContext null
                }

                WsCodec.OP_PING -> try {
                    conn.write(WsCodec.buildFrame(WsCodec.OP_PONG, frame.payload, mask = true))
                } catch (_: Exception) {
                }

                WsCodec.OP_PONG -> Unit

                WsCodec.OP_CONT, WsCodec.OP_TEXT, WsCodec.OP_BINARY -> {
                    if (frame.fin && frag.size() == 0) return@withContext frame.payload
                    frag.write(frame.payload)
                    if (frag.size() > WsCodec.MAX_MESSAGE_LEN) {
                        throw IOException("WS message too large: ${frag.size()} bytes")
                    }
                    if (!frame.fin) continue
                    val message = frag.toByteArray()
                    frag.reset()
                    return@withContext message
                }
            }
        }
        null
    }

    suspend fun close() {
        if (!closed) {
            closed = true
            try {
                conn.write(WsCodec.buildFrame(WsCodec.OP_CLOSE, ByteArray(0), mask = true))
            } catch (_: Exception) {
            }
        }
        conn.closeQuietly()
    }

    fun closeQuietly() {
        closed = true
        conn.closeQuietly()
    }

    companion object {
        /**
         * Порт `RawWebSocket.connect(host, domain, timeout, path, sni, secure)`.
         *
         * [host] — адрес, к которому стучимся (IP DC или домен),
         * [domain] — значение заголовка Host,
         * [sni] — переопределение SNI (фронтинг); при null SNI = domain.
         * Как и в Python, проверка hostname включена только когда [sni] не задан.
         */
        suspend fun connect(
            host: String,
            domain: String,
            timeoutSec: Double = 10.0,
            path: String = Protocol.WS_PATH,
            sni: String? = null,
            secure: Boolean = true,
            bufferSize: Int = 256 * 1024,
        ): RawWebSocket = withContext(Dispatchers.IO) {
            val port = if (secure) 443 else 80
            val handshakeTimeoutMs = (timeoutSec * 1000).toInt()
            val connectTimeoutMs =
                ((if (secure) minOf(timeoutSec, 10.0) else timeoutSec) * 1000).toInt()

            val plain = Socket()
            plain.applyProxyOptions(bufferSize)
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val socket: Socket = try {
                if (secure) {
                    Tls.wrap(
                        socket = plain,
                        sni = sni ?: domain,
                        port = port,
                        verifyHostname = sni == null,
                        timeoutMs = handshakeTimeoutMs,
                    )
                } else {
                    plain.soTimeout = handshakeTimeoutMs
                    plain
                }
            } catch (e: Throwable) {
                try {
                    plain.close()
                } catch (_: Exception) {
                }
                throw e
            }

            val conn = StreamConnection(
                socket,
                socket.getInputStream(),
                socket.getOutputStream(),
                "$host:$domain",
            )
            try {
                val wsKey = Base64.getEncoder().encodeToString(urandom(16))
                val req = buildString {
                    append("GET ").append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(domain).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Sec-WebSocket-Protocol: binary\r\n")
                    append("\r\n")
                }
                conn.write(req.toByteArray(Charsets.US_ASCII))

                val lines = ArrayList<String>()
                while (true) {
                    val line = conn.readLine()
                    if (line.isEmpty() || isBlankLine(line)) break
                    lines.add(String(line, Charsets.UTF_8).trim())
                    if (lines.size > 64) throw IOException("too many handshake lines")
                }
                if (lines.isEmpty()) throw WsHandshakeError(0, "empty response")

                val firstLine = lines[0]
                val parts = firstLine.split(' ', limit = 3)
                val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (statusCode == 101) {
                    conn.setSoTimeoutMs(0) // штатные чтения без таймаута
                    return@withContext RawWebSocket(conn)
                }

                val headers = HashMap<String, String>()
                for (hl in lines.drop(1)) {
                    val idx = hl.indexOf(':')
                    if (idx >= 0) {
                        headers[hl.substring(0, idx).trim().lowercase()] = hl.substring(idx + 1).trim()
                    }
                }
                conn.closeQuietly()
                throw WsHandshakeError(statusCode, firstLine, headers, headers["location"])
            } catch (e: Throwable) {
                conn.closeQuietly()
                throw e
            }
        }

        private fun isBlankLine(line: ByteArray): Boolean =
            (line.size == 1 && line[0] == '\n'.code.toByte()) ||
                (line.size == 2 && line[0] == '\r'.code.toByte() && line[1] == '\n'.code.toByte())
    }
}
