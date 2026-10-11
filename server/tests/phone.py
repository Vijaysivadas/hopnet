"""A minimal Python 'phone' speaking the same protocol as the Android SDK, for tests."""

from __future__ import annotations

import base64
import json
import os
import time

from nacl.public import PrivateKey
from nacl.signing import SigningKey

from hopnet_server import envelope as wire
from hopnet_server.crypto import address_of, b64, build_broadcast_body, open_payload, seal_payload, unb64, verify


class Phone:
    def __init__(self, handle: str, name: str):
        self.handle = handle
        self.name = name
        self.sign = SigningKey.generate()
        self.box = PrivateKey.generate()
        self.server_sign_pk: bytes | None = None
        self.server_enc_pk: bytes | None = None

    @property
    def sign_pk(self) -> bytes:
        return bytes(self.sign.verify_key)

    @property
    def address(self) -> str:
        return address_of(self.sign_pk)

    def register(self, client) -> dict:
        sign_pk, enc_pk = b64(self.sign_pk), b64(bytes(self.box.public_key))
        sig = self.sign.sign(f"HNREG1|{self.handle}|{sign_pk}|{enc_pk}".encode()).signature
        resp = client.post("/v1/register", json={"handle": self.handle, "name": self.name,
                                                  "signPk": sign_pk, "encPk": enc_pk, "sig": b64(sig)})
        resp.raise_for_status()
        body = resp.json()
        self.server_sign_pk = unb64(body["server"]["signPk"])
        self.server_enc_pk = unb64(body["server"]["encPk"])
        return body

    def up(self, svc: str, kind: str, data: dict, trace: list[str] | None = None, hops: int = 0,
           ttl_ms: int = 60_000, signer: SigningKey | None = None) -> tuple[str, bytes]:
        now = int(time.time() * 1000)
        msg_id = os.urandom(16)
        inner = {"v": 1, "id": msg_id.hex(), "from": self.address, "ts": now, "exp": now + ttl_ms,
                 "svc": svc, "kind": kind, "data": data}
        body = seal_payload(json.dumps(inner).encode(), signer or self.sign, self.server_enc_pk)
        env = wire.Envelope(wire.UP, wire.FLAG_TRACE, msg_id, wire.ZERO_ID, hops, 12, now, now + ttl_ms, body,
                            trace or [self.handle])
        return msg_id.hex(), wire.encode(env)

    def broadcast(self, svc: str, kind: str, data: dict, trace: list[str] | None = None, hops: int = 0,
                  ttl_ms: int = 60_000, ts: int | None = None, signer: SigningKey | None = None,
                  dest: bytes | None = None, tamper: dict | None = None) -> tuple[str, bytes]:
        """A signed, unencrypted BROADCAST like Hopnet's Broadcasts.build (e.g. Find Me)."""
        now = ts if ts is not None else int(time.time() * 1000)
        msg_id = os.urandom(16)
        inner = {"v": 1, "id": msg_id.hex(), "from": self.address, "ts": now, "exp": now + ttl_ms,
                 "svc": svc, "kind": kind, "data": data, **(tamper or {})}
        body = build_broadcast_body(json.dumps(inner).encode(), signer or self.sign)
        env = wire.Envelope(wire.BROADCAST, wire.FLAG_TRACE, msg_id, dest or bytes.fromhex(self.address), hops, 16,
                            now, now + ttl_ms, body, trace or [self.handle])
        return msg_id.hex(), wire.encode(env)

    def open_down(self, env: wire.Envelope) -> dict | None:
        opened = open_payload(env.body, self.box)
        if opened is None:
            return None
        sig, js = opened
        if not verify(sig, js, self.server_sign_pk):
            return None
        return json.loads(js)


def send_up(ws, *raw: bytes) -> None:
    ws.send_text(json.dumps({"t": "up", "envs": [base64.b64encode(r).decode() for r in raw]}))


def receive_envelopes(ws) -> list[wire.Envelope]:
    msg = json.loads(ws.receive_text())
    assert msg["t"] == "down"
    return [wire.decode(base64.b64decode(e)) for e in msg["envs"]]


def receive_until(ws, predicate, limit: int = 10) -> wire.Envelope:
    """Reads pushed envelopes until one matches (tests time out via pytest-timeout)."""
    for _ in range(limit):
        for env in receive_envelopes(ws):
            if predicate(env):
                return env
    raise AssertionError("expected envelope never arrived")


def parse_receipt(env: wire.Envelope) -> tuple[str, int, int, str]:
    body = env.body
    target, status, hops, n = body[:16].hex(), body[16], body[17], body[18]
    return target, status, hops, body[19:19 + n].decode()
