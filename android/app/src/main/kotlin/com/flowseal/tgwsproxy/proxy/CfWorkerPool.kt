package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.log.Log
import java.net.URLEncoder
import java.util.ArrayDeque
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Соединение CF Worker + домен, через который оно установлено. */
class PooledWorker(val ws: RawWebSocket, val createdAtMicros: Long, val domain: String)

/**
 * Порт `proxy/pool.py::_CfWorkerPool`.
 *
 * CF Worker — резервный путь: собственный воркер проксирует WS на заданный
 * `dst` (IP:443) для конкретного DC. Как и в оригинале, на DC держится
 * не более [PER_DC_LIMIT] соединения.
 */
class CfWorkerPool(
    private val config: ProxyConfig,
    private val scope: CoroutineScope,
) {
    companion object {
        const val WS_POOL_MAX_AGE_MS = 100_000L
        const val WS_POOL_CHECK_INTERVAL_MS = 5_000L
        const val PER_DC_LIMIT = 1
        const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        const val REFILL_BACKOFF_MAX_MS = 3_600_000L
    }

    private val lock = Any()
    private val idle = HashMap<Int, ArrayDeque<PooledWorker>>()
    private val refilling = HashMap<Int, Job>()
    private val rotating = HashMap<Int, Job>()
    private val refillFailures = HashMap<Int, Int>()
    private val refillAfter = HashMap<Int, Long>()
    private val exhaustedUntil = HashMap<String, Long>()

    private fun nowMicros(): Long = System.nanoTime() / 1000
    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun bucketFor(dc: Int): ArrayDeque<PooledWorker> =
        synchronized(lock) { idle.getOrPut(dc) { ArrayDeque() } }

    /** Порт `get()`: (ws, домен) или null. */
    suspend fun get(
        dc: Int,
        fallbackDst: String,
        workerDomains: List<String>,
    ): Pair<RawWebSocket, String>? {
        if (config.poolSize <= 0 || workerDomains.isEmpty()) return null
        val bucket = bucketFor(dc)
        while (true) {
            val item = synchronized(lock) { bucket.pollFirst() } ?: break
            val ageMs = (nowMicros() - item.createdAtMicros) / 1000
            if (ageMs >= WS_POOL_MAX_AGE_MS || item.ws.isStale) {
                scope.launch { quietClose(item.ws) }
                continue
            }
            Stats.incCfPoolHits()
            Log.debug(
                "CF worker pool hit DC$dc via ${item.domain} " +
                    "(age=${ageMs / 1000}s, left=${bucket.size})",
            )
            scheduleRefill(dc, fallbackDst, workerDomains)
            return item.ws to item.domain
        }
        Stats.incCfPoolMisses()
        scheduleRefill(dc, fallbackDst, workerDomains)
        return null
    }

    /** Порт `available_domains`: перемешанные домены без «исчерпанных». */
    fun availableDomains(workerDomains: List<String>): List<String> {
        val now = System.currentTimeMillis()
        val out = ArrayList<String>(workerDomains.size)
        synchronized(lock) {
            for (domain in workerDomains) {
                if (out.contains(domain)) continue
                val until = exhaustedUntil[domain] ?: 0L
                if (until > now) continue
                if (until != 0L) exhaustedUntil.remove(domain)
                out.add(domain)
            }
        }
        out.shuffle()
        return out
    }

    /**
     * Порт `report_failure`: в оригинале обработка 429 отключена заглушкой
     * (`return # TODO`), поведение сохранено как есть.
     */
    fun reportFailure(workerDomain: String, exc: Exception) {
        // TODO: проверять статус 429 после достижения дневного лимита (как в оригинале)
    }

    private fun scheduleRefill(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        if (config.poolSize <= 0 || workerDomains.isEmpty()) return
        scheduleRotation(dc, fallbackDst, workerDomains)
        synchronized(lock) {
            if (refilling.containsKey(dc)) return
            if (nowMs() < (refillAfter[dc] ?: 0L)) return
            refilling[dc] = scope.launch { refill(dc, fallbackDst, workerDomains.toList()) }
        }
    }

    private suspend fun refill(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        try {
            val bucket = bucketFor(dc)
            val targetSize = min(config.poolSize, PER_DC_LIMIT)
            val needed = targetSize - synchronized(lock) { bucket.size }
            if (needed <= 0) return

            var connected = 0
            for (i in 0 until needed) {
                val result = connectOne(workerDomains, fallbackDst, dc) ?: break
                synchronized(lock) {
                    bucket.addLast(PooledWorker(result.first, nowMicros(), result.second))
                }
                connected++
            }

            synchronized(lock) {
                if (connected > 0) {
                    refillFailures.remove(dc)
                    refillAfter.remove(dc)
                } else {
                    val failures = (refillFailures[dc] ?: 0) + 1
                    refillFailures[dc] = failures
                    val delayMs = min(
                        REFILL_BACKOFF_INITIAL_MS shl min(failures - 1, 12),
                        REFILL_BACKOFF_MAX_MS,
                    )
                    refillAfter[dc] = nowMs() + delayMs
                    Log.info("CF worker pool refill failed for DC$dc, retry in ${delayMs / 1000}s")
                }
            }
            Log.debug("CF worker pool refilled DC$dc: ${synchronized(lock) { bucket.size }} ready")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.debug("CF worker pool refill error: ${e.javaClass.simpleName}")
        } finally {
            synchronized(lock) { refilling.remove(dc) }
        }
    }

    private fun scheduleRotation(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        synchronized(lock) {
            if (rotating.containsKey(dc)) return
            rotating[dc] = scope.launch { rotate(dc, fallbackDst, workerDomains.toList()) }
        }
    }

    private suspend fun rotate(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        try {
            while (config.poolSize > 0) {
                val bucket = bucketFor(dc)
                val now = nowMicros()
                val expired = ArrayList<RawWebSocket>()
                val ready = ArrayList<PooledWorker>()
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
                    Log.debug("CF worker pool rotated DC$dc: ${expired.size} stale, ${ready.size} ready")
                }
                if (ready.size < min(config.poolSize, PER_DC_LIMIT)) {
                    scheduleRefill(dc, fallbackDst, workerDomains)
                }

                val wakeAtMs = synchronized(lock) {
                    var wake = nowMs() + WS_POOL_CHECK_INTERVAL_MS
                    val oldest = bucket.minOfOrNull { it.createdAtMicros }
                    if (oldest != null) wake = min(wake, oldest / 1000 + WS_POOL_MAX_AGE_MS)
                    if (!refilling.containsKey(dc)) {
                        val after = refillAfter[dc] ?: 0L
                        if (after > nowMs()) wake = min(wake, after)
                    }
                    wake
                }
                delay(maxOf(0L, wakeAtMs - nowMs()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.debug("CF worker pool rotation error: ${e.javaClass.simpleName}")
        } finally {
            synchronized(lock) { rotating.remove(dc) }
        }
    }

    /** Порт `_connect_one`: /apiws?dst=...&dc=... через доступные домены. */
    private suspend fun connectOne(
        workerDomains: List<String>,
        fallbackDst: String,
        dc: Int,
    ): Pair<RawWebSocket, String>? {
        val query = "dst=" + URLEncoder.encode(fallbackDst, "UTF-8") + "&dc=$dc"
        val path = "/apiws?$query"
        for (workerDomain in availableDomains(workerDomains)) {
            try {
                val ws = RawWebSocket.connect(
                    host = workerDomain,
                    domain = workerDomain,
                    timeoutSec = 8.0,
                    path = path,
                    secure = !config.disableSecure,
                    bufferSize = config.bufferSize,
                )
                return ws to workerDomain
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.debug(
                    "CF worker pool connect DC$dc via $workerDomain for $fallbackDst " +
                        "failed: ${e.javaClass.simpleName}",
                )
                reportFailure(workerDomain, e)
            }
        }
        return null
    }

    private suspend fun quietClose(ws: RawWebSocket) {
        try {
            ws.close()
        } catch (_: Exception) {
        }
    }

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
            exhaustedUntil.clear()
        }
        tasks.forEach { it.cancel() }
        all.forEach { it.closeQuietly() }
    }

    suspend fun close() {
        val tasks: List<Job>
        synchronized(lock) { tasks = rotating.values.toList() + refilling.values.toList() }
        reset()
        tasks.forEach { it.join() }
    }

    /** Порт `warmup`: разогрев для DC, которых нет в dcRedirects. */
    fun warmup() {
        val cfFallbacks = Protocol.DC_DEFAULT_IPS.filterKeys { it !in config.dcRedirects.keys }
        if (config.poolSize <= 0 || cfFallbacks.isEmpty() || config.cfproxyWorkerDomains.isEmpty()) {
            return
        }
        for ((dc, fallbackDst) in cfFallbacks) {
            scheduleRefill(dc, fallbackDst, config.cfproxyWorkerDomains)
        }
        Log.info("CF worker pool warmup started for ${cfFallbacks.size} DC(s)")
    }
}
