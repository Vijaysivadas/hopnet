"""A scriptable 'phone' that talks to the Hopnet server over the gateway protocol.

Handy for testing a single real device or emulator: the script plays the other person.

  python -m tools.fake_phone --server http://127.0.0.1:8000 --handle ravi --name Ravi \
      --send-to asha --text "Hi from the laptop" --listen 60

It can also ask Live Info questions (repeat --info for several):

  python -m tools.fake_phone --handle ravi --info '{"tool":"brief","lat":10.1076,"lon":76.3516}' --listen 90

It registers (or re-uses saved keys in tools/.fake_<handle>.json), connects as an online
gateway, optionally sends one chat message, then prints every message it receives and acks it.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import time
from pathlib import Path

import httpx
import websockets
from nacl.public import PrivateKey
from nacl.signing import SigningKey

from hopnet_server import envelope as wire
from hopnet_server.crypto import RECEIPT_DOMAIN, STATUS_NAMES, address_of, b64, open_payload, seal_payload, unb64, verify


class FakePhone:
    def __init__(self, server: str, handle: str, name: str):
        self.server = server.rstrip("/")
        self.handle = handle
        self.name = name
        key_file = Path(__file__).parent / f".fake_{handle}.json"
        if key_file.exists():
            saved = json.loads(key_file.read_text())
            self.sign, self.box = SigningKey(unb64(saved["sign"])), PrivateKey(unb64(saved["box"]))
        else:
            self.sign, self.box = SigningKey.generate(), PrivateKey.generate()
            key_file.write_text(json.dumps({"sign": b64(bytes(self.sign)), "box": b64(bytes(self.box))}))
        self.address = address_of(bytes(self.sign.verify_key))
        self.server_sign = self.server_enc = None

    def register(self) -> None:
        sign_pk, enc_pk = b64(bytes(self.sign.verify_key)), b64(bytes(self.box.public_key))
        sig = self.sign.sign(f"HNREG1|{self.handle}|{sign_pk}|{enc_pk}".encode()).signature
        r = httpx.post(f"{self.server}/v1/register", json={"handle": self.handle, "name": self.name,
                                                            "signPk": sign_pk, "encPk": enc_pk, "sig": b64(sig)})
        r.raise_for_status()
        body = r.json()
        self.server_sign, self.server_enc = unb64(body["server"]["signPk"]), unb64(body["server"]["encPk"])
        print(f"registered @{self.handle} address={self.address}")

    def up(self, svc: str, kind: str, data: dict) -> tuple[str, str]:
        now = int(time.time() * 1000)
        msg_id = os.urandom(16)
        inner = {"v": 1, "id": msg_id.hex(), "from": self.address, "ts": now, "exp": now + 86_400_000,
                 "svc": svc, "kind": kind, "data": data}
        body = seal_payload(json.dumps(inner).encode(), self.sign, self.server_enc)
        env = wire.Envelope(wire.UP, wire.FLAG_TRACE, msg_id, wire.ZERO_ID, 0, 12, now, now + 86_400_000, body, ["laptop"])
        return msg_id.hex(), base64.b64encode(wire.encode(env)).decode()

    async def run(self, send_to: str | None, text: str, listen: float, info: list[dict] | None = None) -> None:
        url = self.server.replace("http://", "ws://").replace("https://", "wss://") + "/v1/gateway/ws"
        async with websockets.connect(url) as ws:
            await ws.send(json.dumps({"t": "hello", "node": "laptop", "label": "laptop", "addr": self.address}))
            if send_to:
                mid, env = self.up("chat", "message", {"to": send_to, "text": text})
                await ws.send(json.dumps({"t": "up", "envs": [env]}))
                print(f"sent {mid[:6]} to @{send_to}: {text!r}")
            for query in info or []:
                mid, env = self.up("info", "query", query)
                await ws.send(json.dumps({"t": "up", "envs": [env]}))
                print(f"asked {mid[:6]}: {json.dumps(query, ensure_ascii=False)}")
            deadline = time.time() + listen
            while time.time() < deadline:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=max(0.1, deadline - time.time()))
                except asyncio.TimeoutError:
                    break
                for item in json.loads(raw).get("envs", []):
                    await self.handle_envelope(ws, wire.decode(base64.b64decode(item)))

    async def handle_envelope(self, ws, env: wire.Envelope) -> None:
        if env.type == wire.RECEIPT:
            body = env.body
            ok = verify(body[-64:], RECEIPT_DOMAIN + body[:-64], self.server_sign)
            n = body[18]
            print(f"receipt {STATUS_NAMES.get(body[16])} for {body[:16].hex()[:6]} path={body[19:19 + n].decode()} sig_ok={ok}")
            return
        if env.type != wire.DOWN or env.dest.hex() != self.address:
            return
        opened = open_payload(env.body, self.box)
        if not opened or not verify(opened[0], opened[1], self.server_sign):
            print("could not open/verify a DOWN envelope")
            return
        inner = json.loads(opened[1])
        if inner["svc"] == "info" and inner["kind"] == "answer":
            print_card(inner["data"], len(wire.encode(env)))
        else:
            print(f"DOWN {inner['svc']}/{inner['kind']} via {'>'.join(env.trace)}: {json.dumps(inner['data'], ensure_ascii=False)}")
        _, ack = self.up("sys", "ack", {"ids": [env.id_hex]})
        await ws.send(json.dumps({"t": "up", "envs": [ack]}))


def print_card(d: dict, size: int) -> None:
    """A Live Info answer, roughly as the app shows it."""
    tags = " · ".join(x for x in (d.get("tool"), "AI" if d.get("ai") else None, d.get("lvl")) if x)
    print(f"\n── {d.get('title')}  [{tags}]")
    if d.get("body"):
        print("   " + d["body"].replace("\n", "\n   "))
    for item in d.get("items", []):
        line = " · ".join(x for x in (item.get("s"), item.get("m")) if x)
        read = f"  [read: {item['k']}]" if item.get("k") else ""
        print(f"   • {item['t']}{read}" + (f"\n     {line}" if line else ""))
    print(f"   {d.get('src', '')} · {d.get('bytes', 0):,} B fetched{' (cached)' if d.get('cached') else ''} → "
          f"{d.get('wire')} B through the mesh (envelope {size} B)")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--server", default="http://127.0.0.1:8000")
    p.add_argument("--handle", required=True)
    p.add_argument("--name", default=None)
    p.add_argument("--send-to")
    p.add_argument("--text", default="Hello from the laptop")
    p.add_argument("--listen", type=float, default=30)
    p.add_argument("--info", action="append", type=json.loads, help='Live Info query as JSON, e.g. {"tool":"news","topic":"kerala"}')
    a = p.parse_args()
    phone = FakePhone(a.server, a.handle, a.name or a.handle.title())
    phone.register()
    asyncio.run(phone.run(a.send_to, a.text, a.listen, a.info))


if __name__ == "__main__":
    main()
