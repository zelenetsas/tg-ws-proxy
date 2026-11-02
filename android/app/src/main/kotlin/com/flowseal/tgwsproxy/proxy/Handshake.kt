package com.flowseal.tgwsproxy.proxy

/**
 * Порт `proxy/tg_ws_proxy.py`: разбор MTProto obfs2 init-пакета,
 * генерация relay-init и построение контекста ре-шифровки.
 */

/** Результат [_try_handshake]. */
class HandshakeResult(
    val dc: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    /** 48 байт prekey+iv клиентского потока (нужен для [buildCryptoCtx]). */
    val decPrekeyIv: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is HandshakeResult &&
        dc == other.dc && isMedia == other.isMedia &&
        protoTag.contentEquals(other.protoTag) &&
        decPrekeyIv.contentEquals(other.decPrekeyIv)

    override fun hashCode(): Int =
        ((dc * 31 + (if (isMedia) 1 else 0)) * 31 + protoTag.contentHashCode()) * 31 +
            decPrekeyIv.contentHashCode()
}

/** Четыре AES-CTR потока одного соединения (порт `CryptoCtx`). */
class CryptoCtx(
    val cltDec: AesCtr,   // расшифровать от клиента
    val cltEnc: AesCtr,   // зашифровать клиенту
    val tgEnc: AesCtr,    // зашифровать в Telegram
    val tgDec: AesCtr,    // расшифровать из Telegram
)

/**
 * Порт `_try_handshake`: AES-CTR(de_key, de_iv) поверх всего 64-байтного init,
 * проверка тега протокола в байтах 56..60 и DC-индекса в 60..62 (LE, signed).
 */
fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
    if (handshake.size < Protocol.HANDSHAKE_LEN) return null

    val decPrekeyIv = handshake.copyOfRange(
        Protocol.SKIP_LEN,
        Protocol.SKIP_LEN + Protocol.PREKEY_LEN + Protocol.IV_LEN,
    )
    val decPrekey = decPrekeyIv.copyOfRange(0, Protocol.PREKEY_LEN)
    val decIv = decPrekeyIv.copyOfRange(Protocol.PREKEY_LEN, decPrekeyIv.size)

    val decKey = sha256(decPrekey, secret)
    val decrypted = AesCtr(decKey, decIv).update(handshake)

    val protoTag = decrypted.copyOfRange(
        Protocol.PROTO_TAG_POS, Protocol.PROTO_TAG_POS + 4,
    )
    if (protoTag.contentEquals(Protocol.PROTO_TAG_ABRIDGED) ||
        protoTag.contentEquals(Protocol.PROTO_TAG_INTERMEDIATE) ||
        protoTag.contentEquals(Protocol.PROTO_TAG_SECURE)
    ) {
        val dcIdx = decrypted.readInt16Le(Protocol.DC_IDX_POS)
        return HandshakeResult(
            dc = kotlin.math.abs(dcIdx),
            isMedia = dcIdx < 0,
            protoTag = protoTag,
            decPrekeyIv = decPrekeyIv,
        )
    }
    return null
}

/**
 * Порт `_generate_relay_init`: случайный 64-байтный init с «хвостом»,
 * в который XOR-ом вкладываются тег протокола и знак/номер DC.
 */
fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
    var rnd: ByteArray
    while (true) {
        rnd = urandom(Protocol.HANDSHAKE_LEN)
        if (Protocol.RESERVED_FIRST_BYTES.contains(rnd[0].toInt() and 0xFF)) continue
        if (Protocol.isReservedStart(rnd)) continue
        if (rnd.copyOfRange(4, 8).contentEquals(Protocol.RESERVED_CONTINUE)) continue
        break
    }

    val encKey = rnd.copyOfRange(Protocol.SKIP_LEN, Protocol.SKIP_LEN + Protocol.PREKEY_LEN)
    val encIv = rnd.copyOfRange(
        Protocol.SKIP_LEN + Protocol.PREKEY_LEN,
        Protocol.SKIP_LEN + Protocol.PREKEY_LEN + Protocol.IV_LEN,
    )

    val encryptedFull = AesCtr(encKey, encIv).update(rnd)

    val keystreamTail = ByteArray(8)
    for (i in 0 until 8) {
        keystreamTail[i] = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
    }

    val tailPlain = ByteArray(8)
    System.arraycopy(protoTag, 0, tailPlain, 0, 4)
    val dcBytes = int16Le(dcIdx)
    System.arraycopy(dcBytes, 0, tailPlain, 4, 2)
    val pad = urandom(2)
    System.arraycopy(pad, 0, tailPlain, 6, 2)

    val encryptedTail = ByteArray(8)
    for (i in 0 until 8) {
        encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
    }

    val result = rnd.copyOf()
    System.arraycopy(encryptedTail, 0, result, Protocol.PROTO_TAG_POS, 8)
    return result
}

/** Порт `_build_crypto_ctx`. */
fun buildCryptoCtx(
    clientDecPrekeyIv: ByteArray,
    secret: ByteArray,
    relayInit: ByteArray,
): CryptoCtx {
    val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, Protocol.PREKEY_LEN)
    val cltDecIv = clientDecPrekeyIv.copyOfRange(Protocol.PREKEY_LEN, clientDecPrekeyIv.size)
    val cltDecKey = sha256(cltDecPrekey, secret)

    val cltEncPrekeyIv = clientDecPrekeyIv.reversedBytes()
    val cltEncKey = sha256(cltEncPrekeyIv.copyOfRange(0, Protocol.PREKEY_LEN), secret)
    val cltEncIv = cltEncPrekeyIv.copyOfRange(Protocol.PREKEY_LEN, cltEncPrekeyIv.size)

    val cltDec = AesCtr(cltDecKey, cltDecIv)
    val cltEnc = AesCtr(cltEncKey, cltEncIv)
    // проскрозить клиентский decryptor мимо 64-байтного init
    cltDec.update(Protocol.ZERO_64)

    val relayEncKey = relayInit.copyOfRange(Protocol.SKIP_LEN, Protocol.SKIP_LEN + Protocol.PREKEY_LEN)
    val relayEncIv = relayInit.copyOfRange(
        Protocol.SKIP_LEN + Protocol.PREKEY_LEN,
        Protocol.SKIP_LEN + Protocol.PREKEY_LEN + Protocol.IV_LEN,
    )

    val relayDecPrekeyIv = relayInit
        .copyOfRange(Protocol.SKIP_LEN, Protocol.SKIP_LEN + Protocol.PREKEY_LEN + Protocol.IV_LEN)
        .reversedBytes()
    val relayDecKey = relayDecPrekeyIv.copyOfRange(0, Protocol.KEY_LEN)
    val relayDecIv = relayDecPrekeyIv.copyOfRange(Protocol.KEY_LEN, relayDecPrekeyIv.size)

    val tgEnc = AesCtr(relayEncKey, relayEncIv)
    val tgDec = AesCtr(relayDecKey, relayDecIv)
    tgEnc.update(Protocol.ZERO_64)

    return CryptoCtx(cltDec, cltEnc, tgEnc, tgDec)
}

/** Соответствие тега протокола целому для `MsgSplitter` (порт `proto_int`). */
fun protoIntFor(protoTag: ByteArray): Int = when {
    protoTag.contentEquals(Protocol.PROTO_TAG_ABRIDGED) -> Protocol.PROTO_ABRIDGED_INT
    protoTag.contentEquals(Protocol.PROTO_TAG_INTERMEDIATE) -> Protocol.PROTO_INTERMEDIATE_INT
    else -> Protocol.PROTO_PADDED_INTERMEDIATE_INT
}
