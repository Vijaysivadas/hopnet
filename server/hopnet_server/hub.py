"""Gateway sessions and the post office logic.

Every phone with internet keeps a WebSocket open here and becomes a gateway. The hub:
  * opens and verifies UP envelopes and hands them to the right service handler
  * verifies BROADCAST envelopes (signed, readable by every phone, e.g. Find Me) the same way
  * answers every UP with a signed RECEIPT (idempotently, the same envelope arrives via many paths)
  * keeps a mailbox of DOWN envelopes and pushes each to every gateway that can currently
    reach the recipient (learned from the recipient's own uploads and polls)
  * turns recipients' acks into DELIVERED receipts and lets the service react
"""

from __future__ import annotations

import asyncio
import itertools
import json
import logging
import math
import os
import time
from dataclasses import dataclass, field

from fastapi import WebSocket

from . import envelope as wire
from .crypto import (DELIVERED, PAD_TO, RECEIVED, REJECTED, SIGNATURE_BYTES, STATUS_NAMES, ServerKeys, address_of,
                     b64, build_receipt, open_broadcast, open_payload, seal_payload, unb64, verify)
from .db import Database, MailItem, User

log = logging.getLogger("hopnet.hub")

SUBSCRIPTION_TTL_MS = 10 * 60 * 1000
DOWN_TTL_MS = 24 * 60 * 60 * 1000
CLOCK_SKEW_MS = 10 * 60 * 1000
SEAL_OVERHEAD = 48  # crypto_box_SEALBYTES: ephemeral public key + MAC


def now_ms() -> int:
    return int(time.time() * 1000)


def _down_json(msg_id: str, address: str, now: int, ttl_ms: int, svc: str, kind: str, data: dict) -> bytes:
    inner = {"v": 1, "id": msg_id, "to": address, "ts": now, "exp": now + ttl_ms, "svc": svc, "kind": kind, "data": data}
    return json.dumps(inner, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


@dataclass
class Session:
    ws: WebSocket
    id: int
    connected_at: int
    node: str = ""
    label: str = "?"
    own_address: str | None = None
    subs: dict[str, float] = field(default_factory=dict)  # address -> expiry (ms), inf for own address
    pushed: set[str] = field(default_factory=set)  # DOWN msg ids already sent on this session
    uploads: int = 0
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def send_envelopes(self, envelopes: list[bytes]) -> None:
        if not envelopes:
            return
        msg = json.dumps({"t": "down", "envs": [b64(e) for e in envelopes]})
        async with self.lock:
            await self.ws.send_text(msg)


@dataclass
class Sighting:
    at: int
    path: str
    gateway: str


class ServiceContext:
    """What a service handler may do. Created per request."""

    def __init__(self, hub: "Hub", session: Session | None, request_id: str | None = None):
        self.hub = hub
        self.session = session
        self.request_id = request_id

    def annotate(self, summary: str | None = None, peer: str | None = None, note: str | None = None) -> None:
        """Describe the current request for the debug page (e.g. the chat text and recipient)."""
        if self.request_id:
            self.hub.db.annotate_upload(self.request_id, summary=summary, peer=peer, note=note)

    def now(self) -> int:
        return self.hub.now()

    def user_by_handle(self, handle: str) -> User | None:
        return self.hub.db.user_by_handle(handle)

    def user_by_address(self, address: str) -> User | None:
        return self.hub.db.user_by_address(address)

    async def send_down(self, address: str, svc: str, kind: str, data: dict,
                        ref: str | None = None, origin: str | None = None, ttl_ms: int = DOWN_TTL_MS) -> str | None:
        return await self.hub.send_down(address, svc, kind, data, ref=ref, origin=origin, ttl_ms=ttl_ms)

    async def mark_delivered(self, recipient: str, ids: list[str], trips: dict[str, tuple[str, int]] | None = None) -> None:
        await self.hub.mark_delivered(self.session, recipient, ids, trips)

    def event(self, kind: str, text: str) -> None:
        self.hub.db.event(kind, text, self.now())


class Hub:
    def __init__(self, db: Database, keys: ServerKeys, services: dict, clock=now_ms):
        self.db = db
        self.keys = keys
        self.services = services
        self.now = clock
        self.sessions: dict[int, Session] = {}
        self.sightings: dict[str, Sighting] = {}
        self._ids = itertools.count(1)

    # ---- sessions ----------------------------------------------------------------------

    def open_session(self, ws: WebSocket) -> Session:
        session = Session(ws=ws, id=next(self._ids), connected_at=self.now())
        self.sessions[session.id] = session
        return session

    def close_session(self, session: Session) -> None:
        self.sessions.pop(session.id, None)
        if session.label != "?":
            self.db.event("gateway", f"gateway {session.label} offline", self.now())

    async def on_message(self, session: Session, msg: dict) -> None:
        t = msg.get("t")
        if t == "hello":
            await self.on_hello(session, msg)
        elif t == "up":
            for item in msg.get("envs", [])[:200]:
                try:
                    raw = unb64(item)
                except ValueError:
                    continue
                await self.on_up(session, raw)

    async def on_hello(self, session: Session, msg: dict) -> None:
        session.node = str(msg.get("node", ""))[:32]
        session.label = str(msg.get("label", "?"))[:24] or "?"
        addr = msg.get("addr")
        self.db.event("gateway", f"gateway {session.label} online", self.now())
        if isinstance(addr, str) and self.db.user_by_address(addr):
            session.own_address = addr
            session.subs[addr] = math.inf
            await self.push_pending(session, addr)

    def subscribe(self, session: Session, address: str) -> None:
        if session.subs.get(address) != math.inf:
            session.subs[address] = self.now() + SUBSCRIPTION_TTL_MS

    def subscribers(self, address: str) -> list[Session]:
        now = self.now()
        return [s for s in self.sessions.values() if s.subs.get(address, 0) > now]

    async def _send(self, session: Session, envelopes: list[bytes]) -> None:
        try:
            await session.send_envelopes(envelopes)
        except Exception as exc:  # socket went away mid-send
            log.info("send to %s failed: %s", session.label, exc)

    # ---- uplink ------------------------------------------------------------------------

    async def on_up(self, session: Session, raw: bytes) -> None:
        try:
            env = wire.decode(raw)
        except wire.ProtocolError:
            return
        if env.type == wire.BROADCAST:
            await self.on_broadcast(session, env)
            return
        if env.type != wire.UP:
            return
        session.uploads += 1
        mid = env.id_hex
        path = ">".join(env.trace)

        prior = self.db.get_upload(mid)
        if prior is not None:  # same envelope via another path: replay the receipt
            await self._send(session, [prior.receipt])
            if prior.sender:
                self.subscribe(session, prior.sender)
                await self.push_pending(session, prior.sender)
            return

        now = self.now()
        inner, user, reason = self._open_up(env, now)
        status = REJECTED if reason else RECEIVED
        receipt = wire.encode(build_receipt(env.msg_id, status, env.hops, env.trace, self.keys.signing_key, now))
        svc = inner.get("svc") if inner else None
        kind = inner.get("kind") if inner else None
        self.db.record_upload(mid, user.address if (user and not reason) else None, svc, kind, status,
                              env.hops, path, now, receipt)
        await self._send(session, [receipt])

        if reason:
            self.db.event("reject", f"✗ {mid[:6]} rejected ({reason}) via {path}", now)
            return

        self.subscribe(session, user.address)
        self.sightings[user.address] = Sighting(now, path, session.label)
        if svc != "sys" or kind != "poll":
            self.db.event(svc, f"⇧ @{user.handle} {svc}/{kind} {mid[:6]} · {env.hops} hops · {path}", now)

        handler = self.services[svc]
        try:
            await handler.handle(ServiceContext(self, session, request_id=mid), user, inner)
        except Exception:
            log.exception("service %s failed on %s", svc, mid)
        await self.push_pending(session, user.address)

    async def on_broadcast(self, session: Session, env: wire.Envelope) -> None:
        """A broadcast one of the gateway's mesh neighbours (or the gateway itself) sent to everyone."""
        session.uploads += 1
        mid = env.id_hex
        path = ">".join(env.trace)

        prior = self.db.get_upload(mid)
        if prior is not None:  # same broadcast via another gateway or path
            await self._send(session, [prior.receipt])
            return

        now = self.now()
        inner, address, reason = self._open_broadcast(env, now)
        status = REJECTED if reason else RECEIVED
        receipt = wire.encode(build_receipt(env.msg_id, status, env.hops, env.trace, self.keys.signing_key, now))
        svc = inner.get("svc") if inner else None
        kind = inner.get("kind") if inner else None
        self.db.record_upload(mid, None if reason else address, svc, kind, status, env.hops, path, now, receipt)
        await self._send(session, [receipt])

        if reason:
            self.db.event("reject", f"✗ {mid[:6]} broadcast rejected ({reason}) via {path}", now)
            return

        user = self.db.user_by_address(address)
        who = f"@{user.handle}" if user else f"unregistered {address[:6]}"
        self.db.event(svc, f"📣 {who} {svc}/{kind} {mid[:6]} · {env.hops} hops · {path}", now)
        try:
            await self.services[svc].handle_broadcast(ServiceContext(self, session, request_id=mid), address, user, inner, env)
        except Exception:
            log.exception("service %s failed on broadcast %s", svc, mid)
        if user is not None:  # the sender is reachable through this gateway, like after an UP
            self.subscribe(session, address)
            self.sightings[address] = Sighting(now, path, session.label)
            await self.push_pending(session, address)

    def _open_broadcast(self, env: wire.Envelope, now: int) -> tuple[dict | None, str | None, str | None]:
        opened = open_broadcast(env.body)
        if opened is None:
            return None, None, "bad signature"
        sign_pk, js = opened
        address = address_of(sign_pk)
        if address != env.dest.hex():
            return None, None, "not signed by its sender"
        try:
            inner = json.loads(js)
        except ValueError:
            return None, None, "bad json"
        if not isinstance(inner, dict):
            return None, None, "bad json"
        if (inner.get("from") != address or inner.get("id") != env.id_hex
                or inner.get("ts") != env.created_at or inner.get("exp") != env.expires_at):
            return inner, address, "header mismatch"
        if env.expires_at < now - CLOCK_SKEW_MS:
            return inner, address, "expired"
        handler = self.services.get(inner.get("svc"))
        if handler is None or not handler.accepts_broadcasts:
            return inner, address, f"unknown broadcast service {inner.get('svc')!r}"
        if not isinstance(inner.get("kind"), str) or not isinstance(inner.get("data", {}), dict):
            return inner, address, "bad data"
        return inner, address, None

    def _open_up(self, env: wire.Envelope, now: int) -> tuple[dict | None, User | None, str | None]:
        opened = open_payload(env.body, self.keys.enc_key)
        if opened is None:
            return None, None, "cannot decrypt"
        signature, js = opened
        try:
            inner = json.loads(js)
        except ValueError:
            return None, None, "bad json"
        if not isinstance(inner, dict):
            return None, None, "bad json"
        user = self.db.user_by_address(str(inner.get("from", "")))
        if user is None:
            return inner, None, "unknown sender"
        if not verify(signature, js, user.sign_pk):
            return inner, user, "bad signature"
        if inner.get("id") != env.id_hex or inner.get("exp") != env.expires_at:
            return inner, user, "header mismatch"
        if env.expires_at < now - CLOCK_SKEW_MS:
            return inner, user, "expired"
        if inner.get("svc") not in self.services:
            return inner, user, f"unknown service {inner.get('svc')!r}"
        if not isinstance(inner.get("data", {}), dict):
            return inner, user, "bad data"
        return inner, user, None

    # ---- downlink ----------------------------------------------------------------------

    async def send_down(self, address: str, svc: str, kind: str, data: dict,
                        ref: str | None = None, origin: str | None = None, ttl_ms: int = DOWN_TTL_MS) -> str | None:
        user = self.db.user_by_address(address)
        if user is None:
            return None
        now = self.now()
        msg_id = os.urandom(16)
        js = _down_json(msg_id.hex(), address, now, ttl_ms, svc, kind, data)
        body = seal_payload(js, self.keys.signing_key, user.enc_pk)
        raw = wire.encode(wire.Envelope(wire.DOWN, wire.FLAG_TRACE, msg_id, bytes.fromhex(address), 0,
                                        wire.DEFAULT_MAX_HOPS, now, now + ttl_ms, body, ["cloud"]))
        self.db.mailbox_insert(MailItem(msg_id.hex(), address, svc, kind, ref, origin, raw, now, now + ttl_ms, None))
        for session in self.subscribers(address):
            if msg_id.hex() not in session.pushed:
                session.pushed.add(msg_id.hex())
                self.db.mailbox_note_push(msg_id.hex(), session.label)
                await self._send(session, [raw])
        return msg_id.hex()

    def down_wire_size(self, address: str, svc: str, kind: str, data: dict, ttl_ms: int = DOWN_TTL_MS) -> int:
        """Bytes the DOWN envelope for [data] will take on every hop, without building it: the sealed
        plaintext is padded to PAD_TO, so the size only depends on the JSON length."""
        now = self.now()
        js = _down_json("0" * 32, address, now, ttl_ms, svc, kind, data)
        plain = SIGNATURE_BYTES + 2 + len(js)
        sealed = plain + (-plain) % PAD_TO + SEAL_OVERHEAD
        return wire.HEADER_SIZE + sealed + 1 + 1 + len("cloud")

    async def push_pending(self, session: Session, address: str) -> None:
        fresh = [m for m in self.db.pending_for(address, self.now()) if m.msg_id not in session.pushed]
        for m in fresh:
            session.pushed.add(m.msg_id)
            self.db.mailbox_note_push(m.msg_id, session.label)
        await self._send(session, [m.envelope for m in fresh])

    async def mark_delivered(self, via: Session | None, recipient: str, ids: list[str],
                             trips: dict[str, tuple[str, int]] | None = None) -> None:
        now = self.now()
        trips = trips or {}
        for mid in ids[:200]:
            item = self.db.mailbox_get(str(mid))
            if item is None or item.recipient != recipient:
                continue
            first_time = item.delivered_at is None
            path, hops = trips.get(item.msg_id, (None, None))
            self.db.mark_delivered(item.msg_id, now, path, hops)
            receipt = wire.encode(build_receipt(bytes.fromhex(item.msg_id), DELIVERED, 0, [], self.keys.signing_key, now))
            targets = {s.id: s for s in self.sessions.values() if item.msg_id in s.pushed}
            if via is not None:
                targets[via.id] = via
            for s in targets.values():
                await self._send(s, [receipt])
            if first_time:
                handler = self.services.get(item.svc)
                if handler is not None:
                    try:
                        await handler.on_delivered(ServiceContext(self, via), item)
                    except Exception:
                        log.exception("on_delivered failed for %s", item.msg_id)

    # ---- debug view --------------------------------------------------------------------

    def findme_cases(self) -> list[dict]:
        """Everyone who used Find Me, latest report each: what the police page shows."""
        return [
            {"address": r["address"], "caseId": r["case_id"], "handle": r["handle"], "name": r["name"],
             "verified": r["handle"] is not None, "status": r["status"],
             "lat": r["lat"], "lon": r["lon"], "acc": r["acc"], "fixAt": r["fix_at"],
             "note": r["note"], "battery": r["battery"], "firstAt": r["first_at"], "updatedAt": r["updated_at"],
             "receivedAt": r["received_at"], "hops": r["hops"], "path": r["path"], "gateway": r["gateway"],
             "updates": r["updates"]}
            for r in self.db.findme_cases()
        ]

    def snapshot(self) -> dict:
        now = self.now()
        users = self.db.users()
        by_addr = {u.address: u for u in users}
        return {
            "gateways": [
                {"label": s.label, "node": s.node, "since": s.connected_at, "uploads": s.uploads,
                 "owner": by_addr[s.own_address].handle if s.own_address in by_addr else None,
                 "reaches": sorted(by_addr[a].handle for a, exp in s.subs.items() if exp > now and a in by_addr)}
                for s in self.sessions.values()
            ],
            "users": [
                {"handle": u.handle, "name": u.name, "address": u.address,
                 "lastSeen": self.sightings[u.address].at if u.address in self.sightings else None,
                 "via": self.sightings[u.address].path if u.address in self.sightings else None}
                for u in users
            ],
            "mailbox": [
                {"id": m.msg_id, "to": by_addr[m.recipient].handle if m.recipient in by_addr else m.recipient,
                 "svc": m.svc, "kind": m.kind, "created": m.created_at, "delivered": m.delivered_at}
                for m in self.db.recent_mail()
            ],
            # Every chat message with its full journey: sender ─hops→ gateway → server → gateway ─hops→ recipient.
            "messages": [
                {"id": r["msg_id"], "at": r["received_at"], "from": r["sender_handle"],
                 "to": r["recipient_handle"] or r["peer"], "text": r["summary"], "note": r["note"],
                 "rejected": r["status"] == REJECTED,
                 "upPath": r["up_path"], "upHops": r["up_hops"],
                 "pushedVia": [x for x in (r["pushed_via"] or "").split(",") if x],
                 "downPath": r["delivered_path"], "downHops": r["delivered_hops"],
                 "deliveredAt": r["delivered_at"]}
                for r in self.db.journeys("chat", "message")
            ],
            # Everything that reached the server, including the automatic polls and acks.
            "traffic": [
                {"id": r["msg_id"], "at": r["received_at"], "from": r["sender_handle"], "svc": r["svc"],
                 "kind": r["kind"], "summary": r["summary"], "note": r["note"], "path": r["trace"],
                 "hops": r["hops"], "status": STATUS_NAMES.get(r["status"], "?")}
                for r in self.db.recent_uploads()
            ],
            "findme": self.findme_cases(),
            # Live Info: what was asked, what fetching it cost, what the mesh carried back.
            "info": [
                {"id": r["msg_id"], "at": r["asked_at"], "from": r["handle"], "tool": r["tool"], "query": r["query"],
                 "title": r["title"], "ok": None if r["ok"] is None else bool(r["ok"]), "level": r["level"],
                 "fetched": r["fetched"], "wire": r["wire"], "cached": bool(r["cached"]), "ms": r["ms"],
                 "answeredAt": r["answered_at"], "sources": r["sources"],
                 "upPath": r["up_path"], "upHops": r["up_hops"],
                 "pushedVia": [x for x in (r["pushed_via"] or "").split(",") if x],
                 "downPath": r["delivered_path"], "downHops": r["delivered_hops"], "deliveredAt": r["delivered_at"]}
                for r in self.db.info_recent()
            ],
            "infoTotals": self.db.info_totals(),
            "events": [{"at": r["at"], "kind": r["kind"], "text": r["text"]} for r in self.db.recent_events()],
            "status_names": STATUS_NAMES,
        }
