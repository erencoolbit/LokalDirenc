"""NoiseVectorTest için bağımsız bir Noise uygulamasından (noiseprotocol 0.3.1) vektör üretir.

    pip install noiseprotocol==0.3.1
    python tools/gen_noise_vectors.py core/src/test/resources/noise_xxpsk0_vectors.json
"""
import json
import sys

from noise.connection import Keypair, NoiseConnection

NAME = b"Noise_XXpsk0_25519_ChaChaPoly_SHA256"


def conn(initiator, s, e, psk, prologue):
    n = NoiseConnection.from_name(NAME)
    n.set_psks(psk)
    n.set_prologue(prologue)
    n.set_keypair_from_private_bytes(Keypair.STATIC, s)
    n.set_keypair_from_private_bytes(Keypair.EPHEMERAL, e)
    (n.set_as_initiator if initiator else n.set_as_responder)()
    n.start_handshake()
    return n


def vector(seed, payloads):
    key = lambda tag: bytes((seed * 7 + tag + i) % 256 for i in range(32))
    s_i, e_i, s_r, e_r, psk = key(1), key(50), key(100), key(150), key(200)
    prologue = b"LD-PROTO-1|" + key(250)
    a, b = conn(True, s_i, e_i, psk, prologue), conn(False, s_r, e_r, psk, prologue)
    messages = []
    for i, payload in enumerate(payloads):
        w, r = (a, b) if i % 2 == 0 else (b, a)
        m = w.write_message(payload)
        assert bytes(r.read_message(m)) == payload
        messages.append(bytes(m).hex())
    transport = []
    for plain in [b"", b"merhaba", bytes(range(256))]:
        transport.append({"from": "i", "plain": plain.hex(), "cipher": bytes(a.encrypt(plain)).hex()})
        assert bytes(b.decrypt(bytes.fromhex(transport[-1]["cipher"]))) == plain
        transport.append({"from": "r", "plain": plain.hex(), "cipher": bytes(b.encrypt(plain)).hex()})
        assert bytes(a.decrypt(bytes.fromhex(transport[-1]["cipher"]))) == plain
    return {
        "staticI": s_i.hex(), "ephemeralI": e_i.hex(), "staticR": s_r.hex(), "ephemeralR": e_r.hex(),
        "psk": psk.hex(), "prologue": prologue.hex(),
        "payloads": [p.hex() for p in payloads], "messages": messages,
        "handshakeHash": bytes(a.get_handshake_hash()).hex(), "transport": transport,
    }


vectors = [
    vector(1, [b"", b"", b""]),
    vector(2, [b"ilk", b"ikinci", b"ucuncu"]),
    vector(3, [bytes(range(100)), b"", bytes(range(200))]),
]


def seal_vector(seed, plain):
    key = lambda tag: bytes((seed * 11 + tag + i) % 256 for i in range(32))
    s_i, e_i, s_r = key(1), key(50), key(100)
    prologue = b"LD-MESH-1|" + key(150)
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    r_pub = X25519PrivateKey.from_private_bytes(s_r).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    a = NoiseConnection.from_name(b"Noise_X_25519_ChaChaPoly_SHA256")
    a.set_prologue(prologue)
    a.set_keypair_from_private_bytes(Keypair.STATIC, s_i)
    a.set_keypair_from_private_bytes(Keypair.EPHEMERAL, e_i)
    a.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, r_pub)
    a.set_as_initiator()
    a.start_handshake()
    b = NoiseConnection.from_name(b"Noise_X_25519_ChaChaPoly_SHA256")
    b.set_prologue(prologue)
    b.set_keypair_from_private_bytes(Keypair.STATIC, s_r)
    b.set_as_responder()
    b.start_handshake()
    m = bytes(a.write_message(plain))
    assert bytes(b.read_message(m)) == plain
    return {"senderPriv": s_i.hex(), "ephemeral": e_i.hex(), "recipientPriv": s_r.hex(), "recipientPub": r_pub.hex(),
            "prologue": prologue.hex(), "plain": plain.hex(), "sealed": m.hex()}


seals = [seal_vector(1, b""), seal_vector(2, b"merhaba"), seal_vector(3, bytes(range(256)))]

with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(vectors, f, indent=1)
with open(sys.argv[1].replace("xxpsk0", "x"), "w", encoding="utf-8") as f:
    json.dump(seals, f, indent=1)
print(f"{len(vectors)} + {len(seals)} vector written")
