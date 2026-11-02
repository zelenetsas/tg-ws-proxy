package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.log.Log
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ключ пула: (dc, is_media, is_test_dc) — как tuple в Python-версии. */
data class PoolKey(val dc: Int, val isMedia: Boolean, val isTestDc: Boolean)

/** Соединение в пуле + момент создания (микросекунды). */
class PooledWs(val ws: RawWebSocket, val createdAtMicros: Long)

/**
 * Порт `proxy/pool.py::_WsPool`.
 *
 * Держит «разогретые» WS-соединения к DC Telegram, чтобы первое сообщение
 * клиента не ждало TLS-handshake. Соединения живут не дольше
 * [WS_POOL_MAX_AGE_MS] и обновляются фоновой ротацией.
 */
class WsPool(
    private val config: ProxyConfig,
    private val scope: CoroutineScope,
) {
    companion object {
        const val WS_POOL_MAX_AGE_MS = 120_000L
        const val WS_POOL_CHECK_INTERVAL_MS = 5_000L
        const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        const val REFILL_BACKOFF_MAX_MS = 3_600_000L
        const val FRONTING_SNI = "sprinthost.ru"
        /** Пинговать idle-соединения пула старше этого возраста. */
        const val KEEPALIVE_AFTER_MS = 45_000L
    }

    private val lock = Any()
    private val idle = HashMap<PoolKey, ArrayDeque<PooledWs>>()
    private val refilling = HashMap<PoolKey, Job>()
    private val rotating = HashMap<PoolKey, Job>()
    private val refillFailures = HashMap<PoolKey, Int>()
    private val refillAfter = HashMap<PoolKey, Long>()

    @Volatile
    var tryFrontingFirst: Boolean = false

    private fun nowMicros(): Long = System.nanoTime() / 1000
    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun isStale(item: PooledWs): Boolean =
        nowMicros() - item.createdAtMicros >= WS_POOL_MAX_AGE_MS * 1000 || item.ws.isStale

    private fun bucketFor(key: PoolKey): ArrayDeque<PooledWs> =
        synchronized(lock) { idle.getOrPut(key) { ArrayDeque() } }

    /** Порт `get()`: готовое соединение или null (с запуском refill). */
    suspend fun get(dc: Int, isMedia: Boolean, isTestDc: Boolean = false): RawWebSocket? {
        val targetIp = config.dcRedirects[dc] ?: return null
        if (config.poolSize <= 0) return null

        val key = PoolKey(dc, isMedia, isTestDc)
        val domains = Protocol.wsDomains(dc, isMedia)
        val bucket = bucketFor(key)

        while (true) {
            val item = synchronized(lock) { bucket.pollFirst() } ?: break
            if (isStale(item)) {
                scope.launch { quietClose(item.ws) }
                continue
            }
            Stats.incPoolHits()
            Log.debug(
                "WS pool hit DC$dc${if (isTestDc) "t" else ""}${if (isMedia) "m" else ""} " +
                    "(age=${(nowMicros() - item.createdAtMicros) / 1_000_000}s, left=${bucket.size})",
            )
            scheduleRefill(key, targetIp, domains)
            return item.ws
        }

        Stats.incPoolMisses()
        scheduleRefill(key, targetIp, domains)
        return null
    }

    private fun scheduleRefill(key: PoolKey, targetIp: String, domains: List<String>) {
        if (config.poolSize <= 0) return
        scheduleRotation(key, targetIp, domains)
        synchronized(lock) {
            if (refilling.containsKey(key)) return
            if (nowMs() < (refillAfter[key] ?: 0L)) return
            refilling[key] = scope.launch { refill(key, targetIp, domains) }
        }
    }

    private suspend fun refill(key: PoolKey, targetIp: String, domains: List<String>) {
        try {
            val bucket = bucketFor(key)
            val needed = config.poolSize - synchronized(lock) { bucket.size }
            if (needed <= 0) return

            val path = if (key.isTestDc) Protocol.WS_PATH_TEST else Protocol.WS_PATH
            val results = coroutineScope {
                (0 until needed).map { async { connectOne(targetIp, domains, path) } }.awaitAll()
            }

            var connected = 0
            for (ws in results) {
                if (ws == null) continue
                synchronized(lock) { bucket.addLast(PooledWs(ws, nowMicros())) }
                connected++
            }

            synchronized(lock) {
                if (connected > 0) {
                    refillFailures.remove(key)
                    refillAfter.remove(key)
                } else {
                    val failures = (refillFailures[key] ?: 0) + 1
                    refillFailures[key] = failures
                    val delayMs = min(
                        REFILL_BACKOFF_INITIAL_MS shl min(failures - 1, 12),
                        REFILL_BACKOFF_MAX_MS,
                    )
                    refillAfter[key] = nowMs() + delayMs
                    Log.info(
                        "WS pool refill failed for DC${key.dc}" +
                            "${if (key.isTestDc) "t" else ""}${if (key.isMedia) "m" else ""}, " +
                            "retry in ${delayMs / 1000}s",
                    )
                }
            }
            Log.debug(
                "WS pool refilled DC${key.dc}${if (key.isTestDc) "t" else ""}" +
                    "${if (key.isMedia) "m" else ""}: ${synchronized(lock) { bucket.size }} ready",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.debug("WS pool refill error: ${e.javaClass.simpleName}")
        } finally {
            synchronized(lock) { refilling.remove(key) }
        }
    }

    private fun scheduleRotation(key: PoolKey, targetIp: String, domains: List<String>) {
        synchronized(lock) {
            if (rotating.containsKey(key)) return
            rotating[key] = scope.launch { rotate(key, targetIp, domains) }
        }
    }

    private suspend fun rotate(key: PoolKey, targetIp: String, domains: List<String>) {
        try {
            while (config.poolSize > 0) {
                val bucket = bucketFor(key)
                val now = nowMicros()
                val expired = ArrayList<RawWebSocket>()
                val ready = ArrayList<PooledWs>()
                synchronized(lock) {
                    while (bucket.isNotEmpty()) {
                        val item = bucket.pollFirst()!!
                        if (now - item.createdAtMicros >= WS_POOL_MAX_AGE_MS * 1000 || item.ws.isStale) {
                            expired.add(item.ws)
                        } else {
                            ready.add(item)
                        }
                    }
                    bucket.addAll(ready)
                }

                for (ws in expired) scope.launch { quietClose(ws) }
                if (expired.isNotEmpty()) {
                    Log.debug("WS pool rotated DC${key.dc}: ${expired.size} stale, ${ready.size} ready")
                }

                // Keepalive: ping соединения старше 45 с — против «тихих» разрывов Doze/HyperOS
                val nowMicro = nowMicros()
                for (item in ready) {
                    if (nowMicro - item.createdAtMicros >= KEEPALIVE_AFTER_MS * 1000) {
                        scope.launch {
                            try {
                                item.ws.ping()
                            } catch (e: Exception) {
                                Log.debug("WS pool keepalive ping failed: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                }

                if (ready.size < config.poolSize) scheduleRefill(key, targetIp, domains)

                val wakeAtMs = synchronized(lock) {
                    var wake = nowMs() + WS_POOL_CHECK_INTERVAL_MS
                    val oldest = bucket.minOfOrNull { it.createdAtMicros }
                    if (oldest != null) wake = min(wake, oldest / 1000 + WS_POOL_MAX_AGE_MS)
                    if (!refilling.containsKey(key)) {
                        val after = refillAfter[key] ?: 0L
                        if (after > nowMs()) wake = min(wake, after)
                    }
                    wake
                }
                delay(maxOf(0L, wakeAtMs - nowMs()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.debug("WS pool rotation error: ${e.javaClass.simpleName}")
        } finally {
            synchronized(lock) { rotating.remove(key) }
        }
    }

    /** Порт `_connect_one`: перебор доменов и режимов фронтинга. */
    private suspend fun connectOne(
        targetIp: String,
        domains: List<String>,
        path: String,
    ): RawWebSocket? {
        for (domain in domains) {
            val modes = if (tryFrontingFirst) listOf(true, false) else listOf(false, true)
            for (fronted in modes) {
                try {
                    val ws = RawWebSocket.connect(
                        host = targetIp,
                        domain = domain,
                        timeoutSec = if (fronted) 7.0 else 8.0,
                        path = path,
                        sni = if (fronted) FRONTING_SNI else null,
                        secure = !config.disableSecure,
                        bufferSize = config.bufferSize,
                    )
                    tryFrontingFirst = fronted
                    if (fronted) Stats.incConnectionsFronting()
                    Log.debug("WS pool connected $domain$path via $targetIp (fronting=$fronted)")
                    return ws
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Stats.incWsErrors()
                    Log.debug(
                        "WS pool connect $domain$path via $targetIp (fronting=$fronted): " +
                            e.javaClass.simpleName,
                    )
                }
            }
        }
        return null
    }

    /** Порт `warmup`: заранее поднять пулы для всех DC из конфига. */
    fun warmup() {
        for ((dc, targetIp) in config.dcRedirects) {
            if (targetIp.isEmpty()) continue
            for (isMedia in listOf(false, true)) {
                val key = PoolKey(dc, isMedia, config.forceTestDc)
                scheduleRefill(key, targetIp, Protocol.wsDomains(dc, isMedia))
            }
        }
        Log.info("WS pool warmup started for ${config.dcRedirects.size} DC(s)")
    }

    private suspend fun quietClose(ws: RawWebSocket) {
        try {
            ws.close()
        } catch (_: Exception) {
        }
    }

    /** Порт `reset`: отменить ротацию/refill и закрыть всё. */
    fun reset() {
        val tasks: List<Job>
        val all: List<RawWebSocket>
        synchronized(lock) {
            tasks = rotating.values.toList() + refilling.values.toList()
            all = idle.values.flatMap { bucket -> bucket.map { it.ws } }
            idle.clear()
            refilling.clear()
            rotating.clear()
            refillFailures.clear()
            refillAfter.clear()
            tryFrontingFirst = false
        }
        tasks.forEach { it.cancel() }
        all.forEach { it.closeQuietly() }
    }

    suspend fun close() {
        val tasks: List<Job>
        synchronized(lock) {
            tasks = rotating.values.toList() + refilling.values.toList()
        }
        reset()
        tasks.forEach { it.join() }
    }
}

