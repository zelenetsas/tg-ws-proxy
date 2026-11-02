package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.log.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Порт `proxy/bridge.py::do_fallback` и его helpers.
 *
 * Цепочка попыток (как в оригинале):
 *  1. cf_worker — свой CF Worker (/apiws?dst=...&dc=...);
 *  2. cf — публичные CF-домены (kws<dc>.<домен>), H2-путь — этап 2;
 *  3. tcp — прямое TCP-соединение к IP датацентра, с backoff.
 */
object Fallback {

    /**
     * Порт `do_fallback`. Возвращает true, если один из методов смог
     * установить мост (мост отрабатывает до закрытия сессии).
     */
    suspend fun doFallback(
        client: StreamConnection,
        relayInit: ByteArray,
        label: String,
        dc: Int,
        isTestDc: Boolean,
        isMedia: Boolean,
        ctx: CryptoCtx,
        splitter: MsgSplitter?,
        wsPool: WsPool,
        cfWorkerPool: CfWorkerPool,
        balancer: Balancer,
        config: ProxyConfig,
    ): Boolean {
        val ipTable = if (isTestDc) Protocol.DC_TEST_IPS else Protocol.DC_DEFAULT_IPS
        val fallbackDst = ipTable[dc]
        val useCf = config.fallbackCfproxy && !isTestDc
        val workerDomains = config.cfproxyWorkerDomains

        val methods = ArrayList<String>()
        if (workerDomains.isNotEmpty() && fallbackDst != null) methods.add("cf_worker")
        if (useCf) methods.add("cf")
        if (fallbackDst != null) methods.add("tcp")

        for (method in methods) {
            when (method) {
                "cf_worker" -> {
                    val ok = cfWorkerFallback(
                        client, relayInit, label, ctx, dc, isTestDc, fallbackDst!!, cfWorkerPool, config,
                    )
                    if (ok) return true
                }

                "cf" -> {
                    val ok = cfProxyFallback(
                        client, relayInit, label, ctx, dc, splitter, balancer, config,
                    )
                    if (ok) return true
                }

                "tcp" -> {
                    val ok = tcpFallback(client, fallbackDst!!, 443, relayInit, label, ctx, config)
                    if (ok) return true
                }
            }
        }
        return false
    }

    /** Порт `_cfproxy_worker_fallback`. */
    private suspend fun cfWorkerFallback(
        client: StreamConnection,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        dc: Int,
        isTestDc: Boolean,
        fallbackDst: String,
        cfWorkerPool: CfWorkerPool,
        config: ProxyConfig,
    ): Boolean {
        val workerDomains = config.cfproxyWorkerDomains
        if (workerDomains.isEmpty()) return false
        val mediaTag = " media"

        val pooled = if (isTestDc) null else cfWorkerPool.get(dc, fallbackDst, workerDomains)
        val ws: RawWebSocket
        if (pooled != null) {
            ws = pooled.first
            Log.info(
                "[$label] DC$dc$mediaTag -> CF worker pool hit via ${pooled.second} for $fallbackDst",
            )
        } else {
            var connected: RawWebSocket? = null
            for (workerDomain in cfWorkerPool.availableDomains(workerDomains)) {
                Log.info("[$label] DC$dc$mediaTag -> trying CF worker $workerDomain for $fallbackDst")
                try {
                    connected = RawWebSocket.connect(
                        host = workerDomain,
                        domain = workerDomain,
                        timeoutSec = 10.0,
                        path = "/apiws?dst=${java.net.URLEncoder.encode(fallbackDst, "UTF-8")}&dc=$dc",
                        secure = !config.disableSecure,
                        bufferSize = config.bufferSize,
                    )
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cfWorkerPool.reportFailure(workerDomain, e)
                    Log.warn(
                        "[$label] DC$dc$mediaTag CF worker $workerDomain failed: ${e.javaClass.simpleName}",
                    )
                }
            }
            ws = connected ?: return false
        }

        Stats.incConnectionsCfproxy()
        ws.send(relayInit)
        Bridge.bridgeWsReencrypt(client, ws, label, ctx, dc = dc, isMedia = false, splitter = null)
        return true
    }

    /** Порт `_cfproxy_fallback`: WS через публичные CF-домены kws<dc>.<домен>. */
    private suspend fun cfProxyFallback(
        client: StreamConnection,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        dc: Int,
        splitter: MsgSplitter?,
        balancer: Balancer,
        config: ProxyConfig,
    ): Boolean {
        val mediaTag = " media"
        Log.info("[$label] DC$dc$mediaTag -> trying CF proxy")
        val connectStarted = Bridge.now()

        var ws: RawWebSocket? = null
        var chosenDomain: String? = null
        for (baseDomain in balancer.domainsForDc(dc)) {
            val domain = "kws$dc.$baseDomain"
            try {
                ws = RawWebSocket.connect(
                    host = domain,
                    domain = domain,
                    timeoutSec = 10.0,
                    secure = !config.disableSecure,
                    bufferSize = config.bufferSize,
                )
                chosenDomain = baseDomain
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("[$label] DC$dc$mediaTag CF proxy failed: ${e.javaClass.simpleName}")
            }
        }
        if (ws == null) return false

        Log.debug(
            "[$label] WS CONNECT DC$dc$mediaTag route=cf " +
                "setup_ms=${((Bridge.now() - connectStarted) * 1000).toInt()}",
        )
        if (chosenDomain != null && balancer.updateDomainForDc(dc, chosenDomain)) {
            Log.info("[$label] Switched active CF domain")
        }

        Stats.incConnectionsCfproxy()
        ws.send(relayInit)
        Bridge.bridgeWsReencrypt(client, ws, label, ctx, dc = dc, isMedia = true, splitter = splitter)
        return true
    }

    /** Порт `_tcp_fallback`: прямое TCP-соединение к IP датацентра. */
    private suspend fun tcpFallback(
        client: StreamConnection,
        dst: String,
        port: Int,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        config: ProxyConfig,
    ): Boolean {
        val key = dst to port
        synchronized(Bridge.tcpRetryAfter) {
            if (Bridge.tcpConnecting.contains(key) ||
                Bridge.now() < (Bridge.tcpRetryAfter[key] ?: 0.0)
            ) {
                Log.debug("[$label] TCP fallback to $dst:$port skipped (backoff or connecting)")
                return false
            }
            Bridge.tcpConnecting.add(key)
        }

        var remote: StreamConnection? = null
        try {
            Log.info("[$label] TCP fallback to $dst:$port")
            remote = try {
                val s = Socket()
                s.applyProxyOptions(config.bufferSize)
                s.connect(InetSocketAddress(dst, port), 10_000)
                StreamConnection(s, s.getInputStream(), s.getOutputStream(), "$dst:$port")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(Bridge.tcpRetryAfter) {
                    val failures = (Bridge.tcpFailures[key] ?: 0) + 1
                    Bridge.tcpFailures[key] = failures
                    val delay = minOf(
                        Bridge.TCP_BACKOFF_INITIAL * Math.pow(2.0, minOf(failures - 1, 7).toDouble()),
                        Bridge.TCP_BACKOFF_MAX,
                    )
                    Bridge.tcpRetryAfter[key] = Bridge.now() + delay
                    Log.warn(
                        "[$label] TCP fallback to $dst:$port failed: ${e.javaClass.simpleName}; " +
                            "retry in ${delay.toInt()}s",
                    )
                }
                null
            }

            if (remote == null) return false

            synchronized(Bridge.tcpRetryAfter) {
                Bridge.tcpFailures.remove(key)
                Bridge.tcpRetryAfter.remove(key)
            }
            Stats.incConnectionsTcpFallback()
            remote.write(relayInit)
            Bridge.bridgeTcpReencrypt(client, remote, label, ctx)
            return true
        } finally {
            synchronized(Bridge.tcpRetryAfter) { Bridge.tcpConnecting.remove(key) }
            remote?.closeQuietly()
        }
    }
}
