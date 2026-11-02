package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.log.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Порт `proxy/tg_ws_proxy.py::_run` + `_handle_client`.
 *
 * Локальный MTProto-прокси: принимает TCP-подключения на 127.0.0.1:port,
 * разбирает obfs2 init, извлекает DC и поднимает мост до Telegram
 * (WS-пул -> CF worker -> CF proxy -> прямой TCP).
 */
class ProxyServer(
    val config: ProxyConfig,
) {
    companion object {
        const val BAD_HANDSHAKE_DRAIN_LIMIT = 64 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }

    val balancer = Balancer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsPool = WsPool(config, scope)
    private val cfWorkerPool = CfWorkerPool(config, scope)

    private var serverSocket: ServerSocket? = null
    private val stopping = AtomicBoolean(false)

    @Volatile
    var listening: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Порт `_run`: поднять листенер, пулы и periodic refresh доменов. */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Bridge.resetTcpBackoff()
            wsPool.reset()
            cfWorkerPool.reset()
            Stats.reset()
            stopping.set(false)

            val userDomains = config.cfproxyUserDomains
            if (userDomains.isNotEmpty()) {
                balancer.updateDomainsList(userDomains)
            } else {
                balancer.updateDomainsList(CfDomains.DEFAULT)
                scope.launch { refreshDomainsLoop() }
            }

            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(config.host, config.port))
            serverSocket = server
            listening = true
            lastError = null

            scope.launch { acceptLoop(server) }
            scope.launch { statsLoop() }

            wsPool.warmup()
            cfWorkerPool.warmup()

            val link = Protocol.buildDdLink(config.host, config.port, config.secret)
            Log.info("============================================================")
            Log.info("  Telegram MTProto WS Bridge Proxy (Android)")
            Log.info("  Listening on   ${config.host}:${config.port}")
            Log.info("  Secret:        ${config.secret}")
            for ((dc, ip) in config.dcRedirects.entries.sortedBy { it.key }) {
                Log.info("    DC${dc}: $ip")
            }
            if (config.fallbackCfproxy) {
                val ud = config.cfproxyUserDomains
                Log.info(
                    "  CF proxy:      enabled (${if (ud.isEmpty()) "auto" else ud.joinToString(", ")})",
                )
            }
            if (config.cfproxyWorkerDomains.isNotEmpty()) {
                Log.info(
                    "  CF worker:     enabled (${config.cfproxyWorkerDomains.joinToString(", ")})",
                )
            }
            Log.info("  Connect: $link")
            Log.info("============================================================")
            Result.success(Unit)
        } catch (e: Exception) {
            listening = false
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.error("Failed to start listener on ${config.host}:${config.port}", e)
            Result.failure(e)
        }
    }

    /** Остановка: закрыть листенер, мосты и пулы. */
    suspend fun stop() {
        stopping.set(true)
        listening = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        wsPool.close()
        cfWorkerPool.close()
        Log.info("Proxy stopped. Final stats: ${Stats.snapshot().summary()}")
    }

    /** Ссылка tg://proxy для текущей конфигурации. */
    fun buildLink(fakeTlsDomain: String? = null): String =
        if (fakeTlsDomain.isNullOrEmpty()) {
            Protocol.buildDdLink(config.host, config.port, config.secret)
        } else {
            Protocol.buildEeLink(config.host, config.port, config.secret, fakeTlsDomain)
        }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!stopping.get() && server.isBound && !server.isClosed) {
            try {
                val client = server.accept()
                client.applyProxyOptions(config.bufferSize)
                scope.launch { handleClient(client) }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (stopping.get()) break
                lastError = "accept: ${e.javaClass.simpleName}"
                Log.warn("accept failed: ${e.message}; retry in 1s")
                delay(1_000)
            }
        }
    }

    private suspend fun refreshDomainsLoop() {
        while (!stopping.get()) {
            CfDomains.refresh(balancer, config.cfproxyUserDomains) { url ->
                fetchUrlText(url)
            }
            delay(3_600_000L)
        }
    }

    private suspend fun statsLoop() {
        while (!stopping.get()) {
            delay(60_000L)
            if (!stopping.get()) Log.info("stats: ${Stats.publish().summary()}")
        }
    }

    /** Порт `_handle_client`. */
    private suspend fun handleClient(socket: Socket) {
        Stats.incConnectionsTotal()
        Stats.incConnectionsActive()
        val peer = try {
            socket.inetAddress?.hostAddress ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val label = "$peer:${socket.port}"

        try {
            val client = StreamConnection(
                socket,
                socket.getInputStream(),
                socket.getOutputStream(),
                label,
            )
            client.setSoTimeoutMs(HANDSHAKE_TIMEOUT_MS)

            val handshake = try {
                client.readExactly(Protocol.HANDSHAKE_LEN)
            } catch (e: Exception) {
                Log.debug("[$label] client disconnected before handshake")
                return
            }

            val result = tryHandshake(handshake, config.secretBytes)
            if (result == null) {
                Stats.incConnectionsBad()
                Log.warn("[$label] bad handshake (wrong secret or proto)")
                drainBadHandshake(client)
                return
            }

            var dc = result.dc
            val isMedia = result.isMedia
            val isTestDc = config.forceTestDc || dc >= 10000
            if (dc >= 10000) {
                Log.info("[$label] test DC$dc -> DC${dc - 10000}")
                dc -= 10000
            }
            val protoInt = protoIntFor(result.protoTag)
            Log.debug(
                "[$label] handshake ok: DC$dc${if (isMedia) " media" else ""} proto=0x${
                    String.format(java.util.Locale.US, "%08X", protoInt.toLong() and 0xFFFFFFFFL)
                }",
            )

            val dcIdx = if (isMedia) -dc else dc
            val relayInit = generateRelayInit(result.protoTag, dcIdx)
            val ctx = buildCryptoCtx(result.decPrekeyIv, config.secretBytes, relayInit)
            client.setSoTimeoutMs(0)

            val pooled = wsPool.get(dc, isMedia, isTestDc)
            if (pooled != null) {
                Log.info("[$label] DC$dc${if (isMedia) " media" else ""} -> WS pool hit")
                Stats.incConnectionsWs()
                val splitter = MsgSplitter(protoInt)
                pooled.send(relayInit)
                Bridge.bridgeWsReencrypt(
                    client, pooled, label, ctx,
                    dc = dc, isMedia = isMedia, splitter = splitter,
                )
            } else {
                val mediaTag = if (isMedia) " media" else ""
                Log.info("[$label] DC$dc$mediaTag WS pool unavailable -> fallback")
                val splitter = MsgSplitter(protoInt)
                val ok = Fallback.doFallback(
                    client, relayInit, label, dc, isTestDc, isMedia,
                    ctx, splitter, wsPool, cfWorkerPool, balancer, config,
                )
                if (ok) {
                    Log.info("[$label] DC$dc$mediaTag fallback closed")
                } else {
                    Log.warn("[$label] DC$dc$mediaTag no fallback available")
                }
            }
        } catch (e: CancellationException) {
            Log.debug("[$label] cancelled")
        } catch (e: Exception) {
            Log.error("[$label] unexpected: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            Stats.decConnectionsActive()
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Порт `_drain_bad_handshake`. */
    private suspend fun drainBadHandshake(client: StreamConnection) {
        var remaining = BAD_HANDSHAKE_DRAIN_LIMIT
        try {
            while (remaining > 0) {
                val chunk = client.read(minOf(4096, remaining)) ?: break
                remaining -= chunk.size
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Простая HTTP(S) GET-загрузка текста (для списка CF-доменов).
 */
suspend fun fetchUrlText(url: String, timeoutMs: Int = 10_000): String? =
    withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "tg-ws-proxy")
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return@withContext null
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
