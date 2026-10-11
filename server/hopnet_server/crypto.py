"""Server keys, sealed payloads, broadcasts and receipts (libsodium via PyNaCl).

Mirrors core/.../protocol/SealedPayload.kt, Broadcasts.kt and Receipts.kt.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import struct
from dataclasses import dataclass
from pathlib import Path

from nacl.exceptions import BadSignatureError, CryptoError
from nacl.public import PrivateKey, PublicKey, SealedBox
from nacl.signing import SigningKey, VerifyKey

from .envelope import DEFAULT_MAX_HOPS, RECEIPT, ZERO_ID, Envelope

SIGNATURE_BYTES = 64
PAD_TO = 256
RECEIPT_DOMAIN = b"HNR1"
BROADCAST_DOMAIN = b"HNB1"
SIGN_PK_BYTES = 32
RECEIPT_TTL_MS = 24 * 60 * 60 * 1000

RECEIVED, DELIVERED, REJECTED = 1, 2, 3
STATUS_NAMES = {RECEIVED: "RECEIVED", DELIVERED: "DELIVERED", REJECTED: "REJECTED"}


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(text: str) -> bytes:
    return base64.b64decode(text.encode("ascii"), validate=True)


@dataclass
class ServerKeys:
    signing_key: SigningKey
    enc_key: PrivateKey

    @property
    def sign_pk(self) -> bytes:
        return bytes(self.signing_key.verify_key)

    @property
    def enc_pk(self) -> bytes:
        return bytes(self.enc_key.public_key)

    @classmethod
    def load_or_create(cls, path: Path) -> "ServerKeys":
        if path.exists():
            data = json.loads(path.read_text())
            return cls(SigningKey(unb64(data["sign_seed"])), PrivateKey(unb64(data["enc_sk"])))
        keys = cls(SigningKey.generate(), PrivateKey.generate())
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"sign_seed": b64(bytes(keys.signing_key)), "enc_sk": b64(bytes(keys.enc_key))}))
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        return keys


def verify(signature: bytes, message: bytes, public_key: bytes) -> bool:
    try:
        VerifyKey(public_key).verify(message, signature)
        return True
    except (BadSignatureError, ValueError, TypeError):
        return False


def seal_payload(json_bytes: bytes, signing_key: SigningKey, recipient_enc_pk: bytes) -> bytes:
    """signature(64) | len(u16) | json | zero padding to 256, sealed to the recipient."""
    signature = signing_key.sign(json_bytes).signature
    raw = signature + struct.pack(">H", len(json_bytes)) + json_bytes
    padded = raw + bytes((-len(raw)) % PAD_TO)
    return SealedBox(PublicKey(recipient_enc_pk)).encrypt(padded)


def open_payload(body: bytes, enc_key: PrivateKey) -> tuple[bytes, bytes] | None:
    """Returns (signature, json_bytes) or None. The caller verifies the signature."""
    try:
        plain = SealedBox(enc_key).decrypt(body)
    except (CryptoError, ValueError, TypeError):
        return None
    if len(plain) < SIGNATURE_BYTES + 2:
        return None
    (length,) = struct.unpack_from(">H", plain, SIGNATURE_BYTES)
    start = SIGNATURE_BYTES + 2
    if start + length > len(plain):
        return None
    return plain[:SIGNATURE_BYTES], plain[start:start + length]


def build_broadcast_body(json_bytes: bytes, signing_key: SigningKey) -> bytes:
    """signPk(32) | signature(64 over "HNB1" + json) | len(u16) | json. Signed, not encrypted."""
    signature = signing_key.sign(BROADCAST_DOMAIN + json_bytes).signature
    return bytes(signing_key.verify_key) + signature + struct.pack(">H", len(json_bytes)) + json_bytes


def open_broadcast(body: bytes) -> tuple[bytes, bytes] | None:
    """Returns (sender signPk, json_bytes) if the signature is valid, else None.
    The caller checks the signer's address against the envelope's dest."""
    header = SIGN_PK_BYTES + SIGNATURE_BYTES + 2
    if len(body) < header:
        return None
    sign_pk = body[:SIGN_PK_BYTES]
    signature = body[SIGN_PK_BYTES:SIGN_PK_BYTES + SIGNATURE_BYTES]
    (length,) = struct.unpack_from(">H", body, SIGN_PK_BYTES + SIGNATURE_BYTES)
    if header + length != len(body):
        return None
    js = body[header:]
    if not verify(signature, BROADCAST_DOMAIN + js, sign_pk):
        return None
    return sign_pk, js


def receipt_msg_id(target: bytes, status: int) -> bytes:
    return hashlib.sha256(RECEIPT_DOMAIN + target + bytes([status])).digest()[:16]


def build_receipt(target: bytes, status: int, hops: int, path: list[str], signing_key: SigningKey, now_ms: int) -> Envelope:
    """target(16) | status(1) | hops(1) | traceLen(1) | "A>B>G" | sig(64 over "HNR1" + the rest)."""
    trace = ">".join(path).encode("utf-8")[:255]
    unsigned = target + bytes([status, max(0, min(hops, 255)), len(trace)]) + trace
    signature = signing_key.sign(RECEIPT_DOMAIN + unsigned).signature
    return Envelope(
        type=RECEIPT,
        flags=0,
        msg_id=receipt_msg_id(target, status),
        dest=ZERO_ID,
        hops=0,
        max_hops=DEFAULT_MAX_HOPS,
        created_at=now_ms,
        expires_at=now_ms + RECEIPT_TTL_MS,
        body=unsigned + signature,
    )


def address_of(sign_pk: bytes) -> str:
    return hashlib.sha256(sign_pk).digest()[:16].hex()
