#!/usr/bin/env python3
"""Генерация тест-векторов из ЭТАЛОННОЙ Python-реализации tg-ws-proxy.

Запуск:  python3 tools/gen_kotlin_vectors.py
Результат: android/app/src/test/resources/vectors/vectors.json

Векторы проверяют нативный Kotlin-порт (Android): MTProto handshake,
relay-init, CryptoCtx (AES-CTR чейны), MsgSplitter, кадры WebSocket,
декодер CF-доменов и парсер DC-списков. На работу прокси не влияют.
"""
import hashlib
import json
import os
import random
import struct
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, REPO)

from proxy.utils import (HANDSHAKE_LEN, SKIP_LEN, PREKEY_LEN, IV_LEN,
                         PROTO_TAG_POS, PROTO_TAG_ABRIDGED,
                         PROTO_TAG_INTERMEDIATE, PROTO_TAG_SECURE,
                         PROTO_ABRIDGED_INT, PROTO_INTERMEDIATE_INT)
from proxy._aes import Cipher, algorithms, modes
from proxy.config import _CFPROXY_ENC, CFPROXY_DEFAULT_DOMAINS
from proxy.tg_ws_proxy import _try_handshake, _generate_relay_init, _build_crypto_ctx
from proxy.bridge import MsgSplitter
from proxy.raw_websocket import RawWebSocket, _xor_mask

_BAD_STARTS = (b'HEAD', b'POST', b'GET ', b'\xee\xee\xee\xee',
               b'\xdd\xdd\xdd\xdd', b'\x16\x03\x01\x02', b'OPTI')


def rnd_bytes(rng, n):
    return bytes(rng.getrandbits(8) for _ in range(n))


def make_client_handshake(secret, dc_idx, proto_tag, rng=None):
    """Собирает валидный obfs2 init «от клиента» (как это делает Telegram)."""
    while True:
        rnd = bytearray(rnd_bytes(random if rng is None else rng, HANDSHAKE_LEN))
        if rnd[0] == 0xEF or bytes(rnd[:4]) in _BAD_STARTS or rnd[4:8] == b'\x00\x00\x00\x00':
            continue
        break
    prekey = bytes(rnd[SKIP_LEN:SKIP_LEN + PREKEY_LEN])
    iv = bytes(rnd[SKIP_LEN + PREKEY_LEN:SKIP_LEN + PREKEY_LEN + IV_LEN])
    key = hashlib.sha256(prekey + secret).digest()
    stream = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    stream.update(bytes(rnd[:PROTO_TAG_POS]))
    keystream_tail = stream.update(b'\x00' * 8)
    tail_plain = proto_tag + struct.pack('<h', dc_idx) + bytes(2)
    out = bytearray(rnd)
    out[PROTO_TAG_POS:HANDSHAKE_LEN] = bytes(
        tail_plain[i] ^ keystream_tail[i] for i in range(8))
    return bytes(out), prekey + iv


def splitter_chunks(plain_packets, key, iv):
    """Шифрует пакеты obfs2-потоком и режет поток на случайные TCP-чанки."""
    enc = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    cipher_stream = bytearray()
    for pkt in plain_packets:
        cipher_stream.extend(enc.update(pkt))
    rng = random.Random(99)
    chunks, off = [], 0
    while off < len(cipher_stream):
        take = min(len(cipher_stream) - off, rng.choice([1, 2, 3, 5, 7, 16, 4096]))
        cipher_chunk = bytes(cipher_stream[off:off + take])
        dec = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
        dec.update(bytes(cipher_stream[:off]))
        plain_chunk = bytes(dec.update(cipher_chunk))
        chunks.append([list(cipher_chunk), list(plain_chunk)])
        off += take
    return chunks


def abridged_packets(rng):
    """Корректные abridged-пакеты: header = payload_len/4 (или 0x7F + 3 байта LE)."""
    pkts = []
    for payload_len in (4, 8, 252, 504, 4, 64):
        pkts.append(bytes([payload_len // 4]) + bytes([payload_len % 256] * payload_len))
    # расширенный заголовок: 0x7F + 3-байтный little-endian (payload_len / 4)
    payload_len = 4 * 1000
    n4 = payload_len // 4
    pkts.append(bytes([0x7F, n4 & 0xFF, (n4 >> 8) & 0xFF, (n4 >> 16) & 0xFF])
                + bytes([0xA5] * payload_len))
    return pkts



def intermediate_packets(rng):
    return [struct.pack('<I', ln) + bytes((i * 3) % 256 for i in range(ln))
            for ln in (17, 256, 1024, 8)]


def ws_frame_vectors():
    """
    Компактные векторы для порта RawWebSocket:
      * build  — заголовок кадра (данные воспроизводимы формулой i*7+len mod 256);
      * mask   — детерминированный тест _xor_mask (случайный mask key не сохранить);
      * read   — готовые кадры с фиксированным mask key для тестов парсера.
    """
    build = []
    for opcode in (RawWebSocket.OP_BINARY, RawWebSocket.OP_CLOSE, RawWebSocket.OP_PING):
        for n in (0, 1, 5, 124, 125, 126, 300, 1000, 65535, 65536, 100000):
            data = bytes((i * 7 + n) % 256 for i in range(n))
            for mask in (False, True):
                frame = RawWebSocket._build_frame(opcode, data, mask=mask)
                # префикс до mask key (mask key случаен → не сохраняется)
                cut = 2 if n < 126 else (4 if n < 65536 else 10)
                build.append({
                    'opcode': opcode, 'mask': mask, 'dataLen': n,
                    'prefix': list(frame[:cut]), 'frameLen': len(frame),
                    'maskKeyPos': cut,
                })

    mask = []
    for key in (b'\x00\x00\x00\x00', b'\x61\x62\x63\x64', b'\xff\xee\x00\x11'):
        for n in (0, 1, 3, 4, 5, 7, 512, 1024, 1027):
            data = bytes((i * 3 + n) % 256 for i in range(n))
            mask.append({'key': list(key), 'data': list(data),
                         'masked': list(_xor_mask(data, key))})

    read = []
    keys = [b'\x31\x32\x33\x34', b'\xde\xad\xbe\xef', b'\x00\x01\x02\x03']

    def emit(payload, opcode, fin, mask_key, label):
        fb = (0x80 if fin else 0) | opcode
        n = len(payload)
        cut = 2 if n < 126 else (4 if n < 65536 else 10)
        if mask_key is None:
            if n < 126:
                hdr = struct.pack('>BB', fb, n)
            elif n < 65536:
                hdr = struct.pack('>BBH', fb, 126, n)
            else:
                hdr = struct.pack('>BBQ', fb, 127, n)
            frame = hdr + payload
        else:
            cut = 2 if n < 126 else (4 if n < 65536 else 10)
            if n < 126:
                hdr = struct.pack('>BB', fb, 0x80 | n)
            elif n < 65536:
                hdr = struct.pack('>BBH', fb, 0x80 | 126, n)
            else:
                hdr = struct.pack('>BBQ', fb, 0x80 | 127, n)
            masked = _xor_mask(payload, mask_key)
            frame = hdr + mask_key + masked
        read.append({'label': label, 'frame': list(frame), 'opcode': opcode,
                     'fin': fin, 'payload': list(payload), 'cut': cut,
                     'maskKey': list(mask_key) if mask_key else None})

    emit(b'', RawWebSocket.OP_PONG, True, None, 'empty-unmasked')
    emit(b'simple-binary-payload', RawWebSocket.OP_BINARY, True, keys[0], 'masked-16b')
    emit(bytes(range(126)), RawWebSocket.OP_BINARY, True, keys[1], 'masked-126')
    emit(bytes((i * 5) % 256 for i in range(200)), RawWebSocket.OP_BINARY, True,
         keys[2], 'masked-200')
    emit(bytes((i * 11) % 256 for i in range(1000)), RawWebSocket.OP_BINARY, True,
         None, 'unmasked-1000')
    # фрагментированное сообщение: DATA(fin=0) + CONT(fin=1)
    parts = [b'alpha-payload-1', b'alpha-payload-2']
    stream = bytearray()
    for i, p in enumerate(parts):
        op = RawWebSocket.OP_BINARY if i == 0 else RawWebSocket.OP_CONT
        fin = i == len(parts) - 1
        mk = keys[i]
        fb = (0x80 if fin else 0) | op
        stream += struct.pack('>BB', fb, 0x80 | len(p)) + mk
        stream += _xor_mask(p, mk)
    read.append({'label': 'fragmented', 'frame': list(stream), 'opcode': None,
                 'fin': True, 'payload': list(b''.join(parts)), 'cut': 2,
                 'maskKey': None, 'fragments': len(parts)})
    # CLOSE с кодом причины
    close_body = struct.pack('>H', 1000) + b'bye'
    emit(close_body, RawWebSocket.OP_CLOSE, True, keys[0], 'close-1000')

    return {'build': build, 'mask': mask, 'read': read}



def splitter_vector(plain_packets, proto_int, rng):
    """Прогон шифрованного потока через ЭТАЛОННЫЙ MsgSplitter."""
    key = rnd_bytes(rng, 32)
    iv = rnd_bytes(rng, 16)
    chunks = splitter_chunks(plain_packets, key, iv)
    sp = MsgSplitter(proto_int)
    expected = [list(sp.split(bytes(c), bytes(p))) for c, p in chunks]
    expected.append(list(sp.flush()))
    return {
        'key': key.hex(), 'iv': iv.hex(), 'proto': proto_int,
        'chunks': [[list(c), list(p)] for c, p in chunks],
        'expected': [[list(part) for part in parts] for parts in expected],
    }


def main():
    rng = random.Random(1337)
    sec = rnd_bytes(rng, 16)

    # --- handshake (media DC2, intermediate) --------------------------------
    hs, dec_prekey_iv = make_client_handshake(sec, -2, PROTO_TAG_INTERMEDIATE, rng)
    res = _try_handshake(hs, sec)
    assert res is not None and (res[0], res[1]) == (2, True), res
    vectors = {
        '_comment': 'Generated by tools/gen_kotlin_vectors.py from reference Python impl',
        'handshake': {
            'secret': list(sec), 'init': list(hs),
            'dc': res[0], 'is_media': res[1], 'proto_tag': list(res[2]),
            'dec_prekey_iv': list(res[3]),
        },
        'handshake_cases': [], 'bad_handshake': [], 'relay_init': [],
        'crypto_ctx': {}, 'splitter': {}, 'ws_frames': ws_frame_vectors(),
        'cf_domains': [{'enc': e, 'dec': d}
                       for e, d in zip(_CFPROXY_ENC, CFPROXY_DEFAULT_DOMAINS)],
        'dc_redirects': [],
    }

    for proto, want in ((PROTO_TAG_ABRIDGED, 1), (PROTO_TAG_INTERMEDIATE, -3),
                        (PROTO_TAG_SECURE, 5), (PROTO_TAG_ABRIDGED, -4)):
        h, dpi = make_client_handshake(sec, want, proto, rng)
        r = _try_handshake(h, sec)
        assert r is not None, (proto, want)
        vectors['handshake_cases'].append({
            'init': list(h), 'dc': abs(want), 'is_media': want < 0,
            'proto_tag': list(proto), 'dec_prekey_iv': list(r[3]),
        })

    # --- negative cases ----------------------------------------------------
    for _ in range(25):
        vectors['bad_handshake'].append({'secret': list(sec),
                                         'init': list(rnd_bytes(rng, HANDSHAKE_LEN))})
    h, _ = make_client_handshake(sec, 4, PROTO_TAG_SECURE, rng)
    tampered = bytearray(h)
    tampered[PROTO_TAG_POS] ^= 0xFF          # bad proto tag
    vectors['bad_handshake'].append({'secret': list(sec), 'init': list(tampered)})
    other = rnd_bytes(rng, 16)
    vectors['bad_handshake'].append({'secret': list(other), 'init': list(h)})

    # --- relay_init samples (self-consistency check on Kotlin side) --------
    vectors['relay_init'] = []
    for _ in range(60):
        proto = rng.choice([PROTO_TAG_ABRIDGED, PROTO_TAG_INTERMEDIATE, PROTO_TAG_SECURE])
        dc_idx = rng.choice([-5, -4, -3, -2, -1, 1, 2, 3, 4, 5, 10002])
        vectors['relay_init'].append({'proto_tag': list(proto), 'dc_idx': dc_idx,
                                      'init': list(_generate_relay_init(proto, dc_idx))})

    # --- CryptoCtx keystream chains ---------------------------------------
    relay_init = _generate_relay_init(PROTO_TAG_INTERMEDIATE, -2)
    ctx = _build_crypto_ctx(dec_prekey_iv, sec, relay_init)
    probes = [rnd_bytes(rng, n) for n in (1, 16, 97, 255, 1024, 3)]
    outs = []
    for p in probes:
        outs.append([list(s.update(p)) for s in
                     (ctx.clt_dec, ctx.clt_enc, ctx.tg_enc, ctx.tg_dec)])
    vectors['crypto_ctx'] = {'secret': list(sec),
                             'dec_prekey_iv': list(dec_prekey_iv),
                             'relay_init': list(relay_init),
                             'probes': [list(p) for p in probes], 'outputs': outs}

    # --- MsgSplitter -------------------------------------------------------
    vectors['splitter'] = {
        'abridged': splitter_vector(abridged_packets(rng), PROTO_ABRIDGED_INT, rng),
        'intermediate': splitter_vector(intermediate_packets(rng),
                                        PROTO_INTERMEDIATE_INT, rng),
    }

    # --- DC ip list parsing -------------------------------------------------
    vectors['dc_redirects'] = [
        {'input': ['2:149.154.167.220', '4:149.154.167.51', '203:91.105.192.100'],
         'output': {'2': '149.154.167.220', '4': '149.154.167.51',
                    '203': '91.105.192.100'}},
        {'input': ['1:149.154.175.50'], 'output': {'1': '149.154.175.50'}},
    ]

    out_dir = os.path.join(REPO, 'android', 'app', 'src', 'test', 'resources', 'vectors')
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, 'vectors.json')
    with open(path, 'w') as f:
        json.dump(vectors, f)
    print('OK %s (%d bytes)' % (path, os.path.getsize(path)))
    print('sections:', ', '.join(sorted(vectors)))


if __name__ == '__main__':
    main()
