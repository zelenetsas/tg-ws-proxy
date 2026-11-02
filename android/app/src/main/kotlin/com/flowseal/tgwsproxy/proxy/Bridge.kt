package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.log.Log
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Порт `proxy/bridge.py`: двунаправленные мосты с ре-шифровкой.
 */
object Bridge {

    const val TCP_BACKOFF_INITIAL = 30.0
    const val TCP_BACKOFF_MAX = 3600.0

    val tcpFailures = HashMap<Pair<String, Int>, Int>()
    val tcpRetryAfter = HashMap<Pair<String, Int>, Double>()
    val tcpConnecting = HashSet<Pair<String, Int>>()

    /** Порт `reset_tcp_backoff`. */
    fun resetTcpBackoff() = synchronized(tcpRetryAfter) {
        tcpFailures.clear()
        tcpRetryAfter.clear()
        tcpConnecting.clear()
    }

    fun now(): Double = System.nanoTime() / 1_000_000_000.0

    /** Дождаться первого завершившегося job, затем отменить второй. */
    private suspend fun firstCompleted(a: Job, b: Job) {
        select<Unit> {
            a.onJoin { }
            b.onJoin { }
        }
        a.cancel()
        b.cancel()
        a.join()
        b.join()
    }

    /**
     * Порт `bridge_ws_reencrypt`: TCP(клиент) <-> WS(telegram) с ре-шифровкой.
     *
     * client ciphertext -> decrypt(clt_key) -> encrypt(tg_key) -> WS
     * WS data           -> decrypt(tg_key) -> encrypt(clt_key) -> client
     */
    suspend fun bridgeWsReencrypt(
        client: StreamConnection,
        ws: RawWebSocket,
        label: String,
        ctx: CryptoCtx,
        dc: Int? = null,
        isMedia: Boolean = false,
        splitter: MsgSplitter? = null,
    ) {
        val dcTag = if (dc != null) "DC$dc${if (isMedia) "m" else ""}" else "DC?"
        val startTime = now()
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0
        var downPackets = 0
        var closeReason = "normal"

        coroutineScope {
            val up = launch {
                try {
                    while (true) {
                        val chunk = client.read(65536)
                        if (chunk == null) {
                            val tail = splitter?.flush()
                            if (!tail.isNullOrEmpty()) ws.send(tail[0])
                            break
                        }
                        val n = chunk.size
                        Stats.addBytesUp(n.toLong())
                        upBytes += n
                        upPackets++

                        val plain = ctx.cltDec.update(chunk)
                        val out = ctx.tgEnc.update(plain)

                        if (splitter != null) {
                            val parts = splitter.split(out, plain)
                            if (parts.isEmpty()) continue
                            if (parts.size > 1) ws.sendBatch(parts) else ws.send(parts[0])
                        } else {
                            ws.send(out)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closeReason = "client: ${e.javaClass.simpleName}"
                }
            }

            val down = launch {
                try {
                    while (true) {
                        val data = ws.recv()
                        if (data == null) {
                            if (closeReason == "normal") closeReason = "upstream: ws_close"
                            break
                        }
                        val n = data.size
                        Stats.addBytesDown(n.toLong())
                        downBytes += n
                        downPackets++
                        val plain = ctx.tgDec.update(data)
                        client.write(ctx.cltEnc.update(plain))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    closeReason = "upstream: tcp_reset"
                } catch (e: Exception) {
                    closeReason = "upstream: ${e.javaClass.simpleName}: ${e.message}"
                }
            }

            firstCompleted(up, down)
        }

        val elapsed = now() - startTime
        Log.info(
            "[$label] $dcTag WS session closed ($closeReason): " +
                "^${Protocol.humanBytes(upBytes)} ($upPackets pkts) " +
                "v${Protocol.humanBytes(downBytes)} ($downPackets pkts) " +
                "in ${String.format(Locale.US, "%.1f", elapsed)}s",
        )
        try {
            ws.close()
        } catch (_: Exception) {
        }
        client.closeQuietly()
    }

    /** Порт `_bridge_tcp_reencrypt`: TCP(клиент) <-> TCP(telegram). */
    suspend fun bridgeTcpReencrypt(
        client: StreamConnection,
        remote: StreamConnection,
        label: String,
        ctx: CryptoCtx,
    ) {
        coroutineScope {
            val up = launch {
                try {
                    while (true) {
                        val data = client.read(65536) ?: break
                        Stats.addBytesUp(data.size.toLong())
                        val plain = ctx.cltDec.update(data)
                        remote.write(ctx.tgEnc.update(plain))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.debug("[$label] forward ended: ${e.javaClass.simpleName}")
                }
            }
            val down = launch {
                try {
                    while (true) {
                        val data = remote.read(65536) ?: break
                        Stats.addBytesDown(data.size.toLong())
                        val plain = ctx.tgDec.update(data)
                        client.write(ctx.cltEnc.update(plain))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.debug("[$label] forward ended: ${e.javaClass.simpleName}")
                }
            }
            firstCompleted(up, down)
        }
        client.closeQuietly()
        remote.closeQuietly()
    }
}
