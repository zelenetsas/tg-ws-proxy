package com.flowseal.tgwsproxy.proxy

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Снимок статистики для UI. */
data class StatsSnapshot(
    val connectionsTotal: Long = 0,
    val connectionsActive: Int = 0,
    val connectionsWs: Long = 0,
    val connectionsCfproxy: Long = 0,
    val connectionsTcpFallback: Long = 0,
    val connectionsFronting: Long = 0,
    val connectionsBad: Long = 0,
    val connectionsMasked: Long = 0,
    val wsErrors: Long = 0,
    val poolHits: Long = 0,
    val poolMisses: Long = 0,
    val cfPoolHits: Long = 0,
    val cfPoolMisses: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
) {
    fun summary(): String {
        val poolTotal = poolHits + poolMisses
        val poolS = if (poolTotal > 0) "$poolHits/$poolTotal" else "n/a"
        val cfTotal = cfPoolHits + cfPoolMisses
        val cfS = if (cfTotal > 0) "$cfPoolHits/$cfTotal" else "n/a"
        return "total=$connectionsTotal active=$connectionsActive " +
            "ws=$connectionsWs tcp_fb=$connectionsTcpFallback cf=$connectionsCfproxy " +
            "front=$connectionsFronting bad=$connectionsBad masked=$connectionsMasked " +
            "err=$wsErrors pool=$poolS cf_pool=$cfS " +
            "up=${Protocol.humanBytes(bytesUp)} down=${Protocol.humanBytes(bytesDown)}"
    }
}

/**
 * Порт `proxy/stats.py`. Счётчики атомарны: к ним обращаются из многих
 * корутин одновременно.
 */
object Stats {
    private val connectionsTotalC = AtomicLong()
    private val connectionsWsC = AtomicLong()
    private val connectionsH2C = AtomicLong()
    private val connectionsTcpFallbackC = AtomicLong()
    private val connectionsCfproxyC = AtomicLong()
    private val connectionsFrontingC = AtomicLong()
    private val connectionsBadC = AtomicLong()
    private val connectionsMaskedC = AtomicLong()
    private val wsErrorsC = AtomicLong()
    private val bytesUpC = AtomicLong()
    private val bytesDownC = AtomicLong()
    private val poolHitsC = AtomicLong()
    private val poolMissesC = AtomicLong()
    private val cfPoolHitsC = AtomicLong()
    private val cfPoolMissesC = AtomicLong()

    private val connectionsActiveC = java.util.concurrent.atomic.AtomicInteger()

    var connectionsActive: Int
        get() = connectionsActiveC.get()
        private set(value) = connectionsActiveC.set(value)

    fun incConnectionsActive() = connectionsActiveC.incrementAndGet()
    fun decConnectionsActive() = connectionsActiveC.decrementAndGet()

    val connectionsTotal: Long get() = connectionsTotalC.get()
    val connectionsWs: Long get() = connectionsWsC.get()
    val connectionsH2: Long get() = connectionsH2C.get()
    val connectionsTcpFallback: Long get() = connectionsTcpFallbackC.get()
    val connectionsCfproxy: Long get() = connectionsCfproxyC.get()
    val connectionsFronting: Long get() = connectionsFrontingC.get()
    val connectionsBad: Long get() = connectionsBadC.get()
    val connectionsMasked: Long get() = connectionsMaskedC.get()
    val wsErrors: Long get() = wsErrorsC.get()
    val bytesUp: Long get() = bytesUpC.get()
    val bytesDown: Long get() = bytesDownC.get()
    val poolHits: Long get() = poolHitsC.get()
    val poolMisses: Long get() = poolMissesC.get()
    val cfPoolHits: Long get() = cfPoolHitsC.get()
    val cfPoolMisses: Long get() = cfPoolMissesC.get()

    fun incConnectionsTotal() = connectionsTotalC.incrementAndGet()
    fun incConnectionsWs() = connectionsWsC.incrementAndGet()
    fun incConnectionsH2() = connectionsH2C.incrementAndGet()
    fun incConnectionsTcpFallback() = connectionsTcpFallbackC.incrementAndGet()
    fun incConnectionsCfproxy() = connectionsCfproxyC.incrementAndGet()
    fun incConnectionsFronting() = connectionsFrontingC.incrementAndGet()
    fun incConnectionsBad() = connectionsBadC.incrementAndGet()
    fun incConnectionsMasked() = connectionsMaskedC.incrementAndGet()
    fun incWsErrors() = wsErrorsC.incrementAndGet()
    fun addBytesUp(n: Long) = bytesUpC.addAndGet(n)
    fun addBytesDown(n: Long) = bytesDownC.addAndGet(n)
    fun incPoolHits() = poolHitsC.incrementAndGet()
    fun incPoolMisses() = poolMissesC.incrementAndGet()
    fun incCfPoolHits() = cfPoolHitsC.incrementAndGet()
    fun incCfPoolMisses() = cfPoolMissesC.incrementAndGet()

    private val _flow = MutableStateFlow(StatsSnapshot())
    val flow: StateFlow<StatsSnapshot> = _flow

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        connectionsTotal = connectionsTotal,
        connectionsActive = connectionsActive,
        connectionsWs = connectionsWs,
        connectionsCfproxy = connectionsCfproxy,
        connectionsTcpFallback = connectionsTcpFallback,
        connectionsFronting = connectionsFronting,
        connectionsBad = connectionsBad,
        connectionsMasked = connectionsMasked,
        wsErrors = wsErrors,
        poolHits = poolHits,
        poolMisses = poolMisses,
        cfPoolHits = cfPoolHits,
        cfPoolMisses = cfPoolMisses,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
    )

    /** Обновить [flow] (вызывается из loop-корутины сервиса). */
    fun publish(): StatsSnapshot = snapshot().also { _flow.value = it }

    fun reset() {
        connectionsTotalC.set(0)
        connectionsWsC.set(0)
        connectionsH2C.set(0)
        connectionsTcpFallbackC.set(0)
        connectionsCfproxyC.set(0)
        connectionsFrontingC.set(0)
        connectionsBadC.set(0)
        connectionsMaskedC.set(0)
        wsErrorsC.set(0)
        bytesUpC.set(0)
        bytesDownC.set(0)
        poolHitsC.set(0)
        poolMissesC.set(0)
        cfPoolHitsC.set(0)
        cfPoolMissesC.set(0)
        connectionsActiveC.set(0)
        _flow.value = StatsSnapshot()
    }
}
