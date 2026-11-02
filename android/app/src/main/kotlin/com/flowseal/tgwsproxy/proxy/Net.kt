package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Порт `proxy/utils.py::set_sock_opts`: TCP_NODELAY + размеры буферов. */
fun Socket.applyProxyOptions(bufferSize: Int) {
    try {
        tcpNoDelay = true
    } catch (_: Exception) {
    }
    try {
        if (bufferSize > 0) {
            receiveBufferSize = bufferSize
            sendBufferSize = bufferSize
        }
    } catch (_: Exception) {
    }
}

/** EOF-аналог `asyncio.IncompleteReadError`. */
class EofException(message: String = "connection closed") : IOException(message)

/**
 * Порт `create_ssl_context(check_hostname=...)`.
 *
 * Android не позволяет отключить сверку hostname штатными настройками, поэтому
 * для фронтинга (SNI не совпадает с адресом соединения) используется делегирующий
 * TrustManager: цепочка проверяется системными доверенными корнями, но сверка
 * имени пропускается — ровно как в Python-версии с `check_hostname=False`.
 */
object Tls {

    @Volatile
    private var cachedUnsafe: SSLSocketFactory? = null

    fun socketFactory(verifyHostname: Boolean): SSLSocketFactory =
        if (verifyHostname) SSLSocketFactory.getDefault() as SSLSocketFactory else unsafeFactory()

    private fun unsafeFactory(): SSLSocketFactory {
        cachedUnsafe?.let { return it }
        synchronized(this) {
            cachedUnsafe?.let { return it }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<SkipHostnameTrust>(SkipHostnameTrust()), java.security.SecureRandom())
            }
            return ctx.socketFactory.also { cachedUnsafe = it }
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    fun wrap(
        socket: Socket,
        sni: String,
        port: Int,
        verifyHostname: Boolean,
        timeoutMs: Int,
    ): SSLSocket {
        val ssl = socketFactory(verifyHostname)
            .createSocket(socket, sni, port, true) as SSLSocket
        val params: SSLParameters = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        params.endpointIdentificationAlgorithm = if (verifyHostname) "HTTPS" else null
        ssl.sslParameters = params
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }

    /** Валидирует цепочку системными корнями, игнорирует несовпадение имени. */
    private class SkipHostnameTrust : X509TrustManager {
        private val delegate: X509TrustManager by lazy { systemTrustManager() }

        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
        ) = delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
        ) = delegate.checkServerTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
            delegate.acceptedIssuers
    }
}


/**
 * Обёртка над сокетом с блокирующим IO в [Dispatchers.IO] — замена asyncio
 * `StreamReader/StreamWriter`. Один экземпляр на соединение.
 */
class StreamConnection(
    val socket: Socket?,
    val input: InputStream,
    val output: OutputStream,
    val peerLabel: String,
) {
    @Volatile
    var closed: Boolean = false
        private set

    @Volatile
    private var atEof = false

    @Volatile
    private var pendingError: Throwable? = null

    private val writeLock = Any()
    private val readBuf = ByteArray(65536)

    /** Порт `transport.is_closing()`. */
    val isClosing: Boolean get() = closed || (socket?.isClosed == true)

    /** Порт `reader.at_eof()`. */
    val isAtEof: Boolean get() = atEof

    /** Порт `reader.exception() is not None`. */
    val hasException: Boolean get() = pendingError != null

    /** Порт `reader.read(n)`: null при EOF, повторное исключение при таймауте. */
    suspend fun read(max: Int = readBuf.size): ByteArray? = withContext(Dispatchers.IO) {
        if (closed) return@withContext null
        try {
            val n = input.read(readBuf, 0, minOf(max, readBuf.size))
            if (n < 0) {
                atEof = true
                null
            } else {
                readBuf.copyOf(n)
            }
        } catch (e: IOException) {
            pendingError = e
            when (e) {
                is SocketTimeoutException, is InterruptedIOException -> throw e
                else -> {
                    atEof = true
                    null
                }
            }
        }
    }

    /** Порт `reader.readexactly(n)` — бросает [EofException] при обрыве. */
    suspend fun readExactly(n: Int): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = try {
                input.read(out, got, n - got)
            } catch (e: IOException) {
                pendingError = e
                atEof = true
                throw EofException("readexactly($n) failed after $got bytes")
            }
            if (r < 0) {
                atEof = true
                throw EofException("expected $n bytes, got $got")
            }
            got += r
        }
        out
    }

    /** Порт `reader.readline()` — для HTTP-заголовков WS handshake. */
    suspend fun readLine(maxBytes: Int = 8192): ByteArray = withContext(Dispatchers.IO) {
        val line = ByteArrayOutputStream(64)
        while (line.size() < maxBytes) {
            val b = try {
                input.read()
            } catch (e: IOException) {
                pendingError = e
                atEof = true
                break
            }
            if (b < 0) {
                atEof = true
                break
            }
            line.write(b)
            if (b == '\n'.code) break
        }
        line.toByteArray()
    }

    /** Порт `writer.write(...)` + `await writer.drain()`. */
    suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            if (closed) throw EofException("socket closed")
            output.write(data)
            output.flush()
        }
    }

    /** Порт отправки нескольких фреймов одним пакетом (send_batch). */
    suspend fun writeAll(frames: List<ByteArray>) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            if (closed) throw EofException("socket closed")
            for (f in frames) output.write(f)
            output.flush()
        }
    }

    fun setSoTimeoutMs(ms: Int) {
        try {
            socket?.soTimeout = ms
        } catch (_: Exception) {
        }
    }

    fun closeQuietly() {
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        suspend fun connect(
            host: String,
            port: Int,
            timeoutMs: Int,
            bufferSize: Int,
        ): StreamConnection = withContext(Dispatchers.IO) {
            val s = Socket()
            s.applyProxyOptions(bufferSize)
            s.connect(InetSocketAddress(host, port), timeoutMs)
            StreamConnection(s, s.getInputStream(), s.getOutputStream(), "$host:$port")
        }
    }
}
