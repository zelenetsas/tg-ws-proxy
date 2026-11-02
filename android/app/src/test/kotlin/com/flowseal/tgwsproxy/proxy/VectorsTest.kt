package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кросс-тесты Kotlin-порта против эталонной Python-реализации.
 * Векторы сгенерированы `tools/gen_kotlin_vectors.py` (тот же код, что
 * работает в проде на десктопе).
 */
class VectorsTest {

    private val vectors: Map<String, Any> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("vectors/vectors.json")!!
        val text = stream.bufferedReader(Charsets.UTF_8).readText()
        MiniJson.parse(text) as Map<String, Any>
    }

    private fun section(name: String): Map<String, Any> = vectors[name] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun listOfMaps(name: String): List<Map<String, Any>> =
        vectors[name] as List<Map<String, Any>>

    private fun Map<String, Any>.bytes(key: String): ByteArray = MiniJson.toBytes(this[key])

    // --- handshake ---------------------------------------------------------

    @Test
    fun handshakeMainVector() {
        val hs = section("handshake")
        val result = tryHandshake(hs.bytes("init"), hs.bytes("secret"))
        assertNotNull(result)
        assertEquals((hs["dc"] as Number).toInt(), result!!.dc)
        assertEquals(hs["is_media"] as Boolean, result.isMedia)
        assertArrayEquals(hs.bytes("proto_tag"), result.protoTag)
        assertArrayEquals(hs.bytes("dec_prekey_iv"), result.decPrekeyIv)
    }

    @Test
    fun handshakeAllProtocols() {
        val secret = section("handshake").bytes("secret")
        for (case in listOfMaps("handshake_cases")) {
            val result = tryHandshake(case.bytes("init"), secret)
            assertNotNull("case ${case["dc"]}", result)
            assertEquals((case["dc"] as Number).toInt(), result!!.dc)
            assertEquals(case["is_media"] as Boolean, result.isMedia)
            assertArrayEquals(case.bytes("proto_tag"), result.protoTag)
            assertArrayEquals(case.bytes("dec_prekey_iv"), result.decPrekeyIv)
        }
    }

    @Test
    fun badHandshakesRejected() {
        val mainSecret = section("handshake").bytes("secret")
        for (case in listOfMaps("bad_handshake")) {
            val secret = if (case.containsKey("secret")) case.bytes("secret") else mainSecret
            assertNull("bad init accepted", tryHandshake(case.bytes("init"), secret))
        }
    }

    // --- relay_init ----------------------------------------------------------

    @Test
    fun relayInitSelfConsistent() {
        for (case in listOfMaps("relay_init")) {
            val init = case.bytes("init")
            val protoTag = case.bytes("proto_tag")
            val dcIdx = (case["dc_idx"] as Number).toInt()

            assertEquals(Protocol.HANDSHAKE_LEN, init.size)
            assertFalse(Protocol.RESERVED_FIRST_BYTES.contains(init[0].toInt() and 0xFF))
            assertFalse(Protocol.isReservedStart(init))
            assertFalse(init.copyOfRange(4, 8).contentEquals(Protocol.RESERVED_CONTINUE))

            // «Релей» воспринимает собственный init: хвост расшифровывается
            // и содержит те же protoTag и dc_idx, что и на входе.
            val encKey = init.copyOfRange(Protocol.SKIP_LEN, Protocol.SKIP_LEN + Protocol.PREKEY_LEN)
            val encIv = init.copyOfRange(
                Protocol.SKIP_LEN + Protocol.PREKEY_LEN,
                Protocol.SKIP_LEN + Protocol.PREKEY_LEN + Protocol.IV_LEN,
            )
            val aes = AesCtr(encKey, encIv)
            aes.update(init.copyOfRange(0, 56))
            val keystream = aes.update(ByteArray(8))
            val tailPlain = ByteArray(8) {
                (init[56 + it].toInt() xor keystream[it].toInt()).toByte()
            }
            assertArrayEquals(protoTag, tailPlain.copyOfRange(0, 4))
            assertEquals("dc_idx tail", dcIdx, tailPlain.readInt16Le(4))
        }
    }

    // --- CryptoCtx keystreams -------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    @Test
    fun cryptoCtxKeystreamsMatchReference() {
        val cc = section("crypto_ctx")
        val ctx = buildCryptoCtx(
            cc.bytes("dec_prekey_iv"),
            cc.bytes("secret"),
            cc.bytes("relay_init"),
        )
        val probes = (cc["probes"] as List<List<Double>>).map { MiniJson.toBytes(it) }
        val outputs = cc["outputs"] as List<List<List<Double>>>
        assertEquals(probes.size, outputs.size)

        for (k in probes.indices) {
            val p = probes[k]
            val want = outputs[k]
            assertArrayEquals("probe #$k clt_dec", MiniJson.toBytes(want[0]), ctx.cltDec.update(p))
            assertArrayEquals("probe #$k clt_enc", MiniJson.toBytes(want[1]), ctx.cltEnc.update(p))
            assertArrayEquals("probe #$k tg_enc", MiniJson.toBytes(want[2]), ctx.tgEnc.update(p))
            assertArrayEquals("probe #$k tg_dec", MiniJson.toBytes(want[3]), ctx.tgDec.update(p))
        }
    }

    // --- MsgSplitter -----------------------------------------------------------

    @Test
    fun splitterAbridged() {
        @Suppress("UNCHECKED_CAST")
        runSplitterVector(section("splitter")["abridged"] as Map<String, Any>)
    }

    @Test
    fun splitterIntermediate() {
        @Suppress("UNCHECKED_CAST")
        runSplitterVector(section("splitter")["intermediate"] as Map<String, Any>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runSplitterVector(sp: Map<String, Any>) {
        // 0xEFEFEFEF в JSON — положительное double; прямой .toInt() в Kotlin
        // даёт Int.MAX_VALUE (clamp!), поэтому сначала toLong().toInt() — как в Java.
        val proto = (sp["proto"] as Number).toLong().toInt()
        val splitter = MsgSplitter(proto)
        val aes = AesCtr(hex(sp["key"] as String), hex(sp["iv"] as String))
        val chunks = sp["chunks"] as List<List<List<Double>>>
        val expected = sp["expected"] as List<List<List<Double>>>

        for ((index, chunkPair) in chunks.withIndex()) {
            val cipherChunk = MiniJson.toBytes(chunkPair[0])
            val plainChunk = MiniJson.toBytes(chunkPair[1])
            // независимая проверка plain-части (AES-CTR потока в позиции чанка)
            assertArrayEquals("chunk #$index plain", plainChunk, aes.update(cipherChunk))

            val parts = splitter.split(cipherChunk, plainChunk)
            val want = expected[index].map { MiniJson.toBytes(it) }
            assertEquals(
                "chunk #$index part count (chunkSize=${cipherChunk.size}, " +
                    "plainHead=${plainChunk.take(4).joinToString(",") { it.toInt().toString() }}, " +
                    "gotLens=${parts.map { it.size }}, wantLens=${want.map { it.size }})",
                want.size,
                parts.size,
            )
            for (i in want.indices) {
                assertArrayEquals("chunk #$index part #$i", want[i], parts[i])
            }
        }

        // последний элемент expected — результат flush()
        val flushExpected = expected.last().map { MiniJson.toBytes(it) }
        val tail = splitter.flush()
        assertEquals(flushExpected.size, tail.size)
        if (flushExpected.isNotEmpty()) assertArrayEquals(flushExpected[0], tail[0])
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = copyOf(size + other.size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // --- WebSocket frames -------------------------------------------------------

    @Test
    fun wsFrameBuildMatchesReference() {
        @Suppress("UNCHECKED_CAST")
        val build = section("ws_frames")["build"] as List<Map<String, Any>>
        for (case in build) {
            val opcode = (case["opcode"] as Number).toInt()
            val len = (case["dataLen"] as Number).toInt()
            val data = ByteArray(len) { ((it * 7 + len) % 256).toByte() }
            val frame = WsCodec.buildFrame(opcode, data, case["mask"] as Boolean)
            val prefix = MiniJson.toBytes(case["prefix"])
            assertArrayEquals(
                "opcode=$opcode len=$len mask=${case["mask"]}",
                prefix,
                frame.copyOfRange(0, prefix.size),
            )
            assertEquals((case["frameLen"] as Number).toInt(), frame.size)
        }
    }

    @Test
    fun wsXorMaskMatchesReference() {
        @Suppress("UNCHECKED_CAST")
        val cases = section("ws_frames")["mask"] as List<Map<String, Any>>
        for (case in cases) {
            val data = MiniJson.toBytes(case["data"])
            val key = MiniJson.toBytes(case["key"])
            val masked = MiniJson.toBytes(case["masked"])
            assertArrayEquals("len=${data.size}", masked, WsCodec.xorMask(data, key))
            assertArrayEquals(data, WsCodec.xorMask(masked, key))
        }
    }

    @Test
    fun wsFrameReadMatchesReference() {
        @Suppress("UNCHECKED_CAST")
        val cases = section("ws_frames")["read"] as List<Map<String, Any>>
        for (case in cases) {
            val input = java.io.ByteArrayInputStream(MiniJson.toBytes(case["frame"]))
            val opVal = case["opcode"]
            if (opVal !is Number) {
                // фрагментированное сообщение: DATA(fin=0) + CONT(fin=1)
                val f1 = WsCodec.readFrame(input)
                val f2 = WsCodec.readFrame(input)
                assertEquals(WsCodec.OP_BINARY, f1.opcode)
                assertEquals(WsCodec.OP_CONT, f2.opcode)
                assertFalse(f1.fin)
                assertTrue(f2.fin)
                assertArrayEquals(MiniJson.toBytes(case["payload"]), f1.payload + f2.payload)
            } else {
                val frame = WsCodec.readFrame(input)
                val opcode = (case["opcode"] as Number).toInt()
                assertEquals("label=${case["label"]}", opcode, frame.opcode)
                assertEquals(case["fin"] as Boolean, frame.fin)
                assertArrayEquals(
                    "label=${case["label"]}",
                    MiniJson.toBytes(case["payload"]),
                    frame.payload,
                )
                if (opcode == WsCodec.OP_CLOSE) {
                    assertEquals(1000, WsCodec.parseClose(frame.payload).first)
                }
            }
        }
    }

    // --- CF domains / dc list -----------------------------------------------------

    @Test
    fun cfDomainsDecodeMatchesReference() {
        val cases = listOfMaps("cf_domains")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            assertEquals(case["dec"], CfDomains.decode(case["enc"] as String))
        }
    }

    @Test
    fun dcRedirectParsingMatchesReference() {
        for (case in listOfMaps("dc_redirects")) {
            @Suppress("UNCHECKED_CAST")
            val input = case["input"] as List<String>
            @Suppress("UNCHECKED_CAST")
            val output = case["output"] as Map<String, String>
            val parsed = parseDcIpList(input)
            assertEquals(output.size, parsed.size)
            for ((k, v) in output) assertEquals(v, parsed[k.toInt()])
        }
    }
}
