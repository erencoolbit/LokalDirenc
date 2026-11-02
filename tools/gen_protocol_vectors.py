"""Protokol baytlarını Kotlin kodundan bağımsız olarak üretir (docs/PROTOKOL.md).

Başka bir dilde yazılan uygulama bu dosyadaki değerlerle aynı sonucu vermelidir.

    pip install cryptography
    python tools/gen_protocol_vectors.py core/src/jvmTest/resources/protocol_vectors.json
"""
import base64
import hashlib
import hmac
import json
import struct
import sys

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def sha256(b):
    return hashlib.sha256(b).digest()


def checksum(body):
    d = sha256(b"LD-CHK-v1" + body.encode())
    bits = (d[0] << 2) | (d[1] >> 6)
    return ALPHABET[bits >> 5] + ALPHABET[bits & 31]


def derive(body, info):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=info.encode()).derive(body.encode())


def canon(*values):
    return b"".join(struct.pack(">I", len(v.encode())) + v.encode() for v in values)


def ed_pub(priv):
    return Ed25519PrivateKey.from_private_bytes(priv).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def ed_sign(priv, msg):
    return Ed25519PrivateKey.from_private_bytes(priv).sign(msg)


def seed(tag, n=32):
    return bytes((tag * 13 + i * 7) % 256 for i in range(n))


def aead(key, nonce, plain):
    return ChaCha20Poly1305(key).encrypt(nonce, plain, b"")


def network(body):
    code = body + checksum(body)
    nid = sha256(b"LD-NET-v1-U" + body.encode())
    disc = derive(body, "LD-DISC-v1")
    lora = derive(body, "LD-LORA-v1")
    token = hmac.new(disc, b"LD-BLE-v1|2980000", hashlib.sha256).digest()[:10]
    advert = token + struct.pack(">H", 0x0081) + struct.pack(">I", 0xDEADBEEF)

    # Yerel ağ yayını: [nonce 12][şifreli: uzunluk 2 + JSON + dolgu, toplam 240][etiket 16]
    beacon_json = json.dumps({"port": 4242, "fp": "ab" * 32, "nick": "piton"}, separators=(",", ":")).encode()
    plain = (struct.pack(">H", len(beacon_json)) + beacon_json).ljust(240, b"\0")
    nonce = seed(1, 12)
    beacon = nonce + aead(disc, nonce, plain)

    # İmzalı herkese açık zarf: imza ortak kanonik baytların üstünde
    author = seed(2)
    issued = 1800000000000
    body_b64 = base64.b64encode(json.dumps({"text": "merhaba"}, separators=(",", ":")).encode()).decode()
    message = b"LD-MESH-1|" + nid + f"|public|{issued}|{body_b64}".encode()
    signature = ed_sign(author, message)
    wire = {"kind": "public", "issuedAt": issued, "body": body_b64, "author": ed_pub(author).hex(), "sig": signature.hex(), "hops": 5}

    # Sıkı LoRa kodlaması ve tek parçalı çerçeve
    compact = bytes([1]) + struct.pack(">q", issued) + bytes([5]) + ed_pub(author) + signature + base64.b64decode(body_b64)
    fragment = bytes.fromhex("01020304") + bytes([0, 1]) + compact
    frame_nonce = seed(3, 12)
    frame = bytes([3, 1]) + frame_nonce + aead(lora, frame_nonce, fragment)

    return {
        "body": body, "code": code, "networkId": nid.hex(),
        "psk": derive(body, "LD-PSK-v1").hex(), "discoveryKey": disc.hex(), "loraKey": lora.hex(),
        "bleEpoch": 2980000, "bleToken": token.hex(), "bleAdvert": advert.hex(),
        "beacon": beacon.hex(), "beaconPort": 4242, "beaconFp": "ab" * 32, "beaconNick": "piton",
        "wire": wire, "compactWire": compact.hex(), "loraFrame": frame.hex(),
    }


def governance():
    keys = [seed(10 + i) for i in range(3)]
    pubs = sorted(ed_pub(k).hex() for k in keys)
    canonical = canon("2", *pubs)
    nid = sha256(b"LD-NET-v1" + canonical).hex()
    issued = 1800000000000
    payload = json.dumps({"keys": {"threshold": 2, "keys": pubs}, "definition": {"name": "Mahalle", "adminTermExpiry": issued + 86400000}}, separators=(",", ":"))
    signed = canon("LD-GOV-v1", nid, "genesis", "0", "", str(issued), payload)
    return {
        "threshold": 2, "keys": pubs, "networkId": nid, "fingerprint": sha256(canonical).hex(),
        "event": {
            "networkId": nid, "type": "genesis", "sequence": 0, "prevHash": "", "issuedAt": issued, "payload": payload,
            "signatures": [{"key": ed_pub(k).hex(), "sig": ed_sign(k, signed).hex()} for k in keys[:2]],
        },
        "eventHash": sha256(signed).hex(), "now": issued,
    }


def merkle():
    chunks = [b"a" * 10, b"b" * 20, b"c" * 5]
    leaves = [sha256(b"\0" + c) for c in chunks]
    level = leaves
    while len(level) > 1:
        next_level = []
        for i in range(0, len(level), 2):
            pair = level[i:i + 2]
            next_level.append(sha256(b"\x01" + pair[0] + pair[1]) if len(pair) == 2 else pair[0])
        level = next_level
    return {"chunks": [c.hex() for c in chunks], "leaves": [x.hex() for x in leaves], "root": level[0].hex()}


vectors = {
    "networks": [network("A" * 26), network("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), network("234567234567234567234567AB")],
    "governance": governance(),
    "merkle": merkle(),
    "sos": {"session": 305419896, "battery": 42, "uuid": "4c442d534f5340008000" + "01" + "12345678" + "2a"},
    "canon": {"values": ["ab", "c", ""], "bytes": canon("ab", "c", "").hex()},
}
with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(vectors, f, indent=1, ensure_ascii=False)
print("vektorler yazildi")
