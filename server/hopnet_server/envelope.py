"""Hopnet envelope wire format. Mirrors core/.../protocol/Envelope.kt byte for byte.

magic(1)=0x48 | version(1) | type(1) | flags(1) | msgId(16) | dest(16) | hops(1) | maxHops(1)
| createdAt(8, ms) | expiresAt(8, ms) | bodyLen(2) | body | traceCount(1) | (len(1) + utf8)*
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field

MAGIC = 0x48
VERSION = 1
UP, DOWN, RECEIPT, BROADCAST = 1, 2, 3, 4
TYPE_NAMES = {UP: "UP", DOWN: "DOWN", RECEIPT: "RECEIPT", BROADCAST: "BROADCAST"}
FLAG_TRACE = 0x01
MAX_BODY = 4096
MAX_TRACE = 16
MAX_LABEL_BYTES = 24
DEFAULT_MAX_HOPS = 12
ZERO_ID = bytes(16)

_HEADER = struct.Struct(">BBBB16s16sBBqqH")
HEADER_SIZE = _HEADER.size  # 56


class ProtocolError(ValueError):
    pass


@dataclass
class Envelope:
    type: int
    flags: int
    msg_id: bytes
    dest: bytes
    hops: int
    max_hops: int
    created_at: int
    expires_at: int
    body: bytes
    trace: list[str] = field(default_factory=list)

    @property
    def id_hex(self) -> str:
        return self.msg_id.hex()


def _label_bytes(label: str) -> bytes:
    data = label.encode("utf-8")
    while len(data) > MAX_LABEL_BYTES:
        label = label[:-1]
        data = label.encode("utf-8")
    return data


def encode(env: Envelope) -> bytes:
    if len(env.body) > MAX_BODY:
        raise ProtocolError("body too large")
    out = bytearray(
        _HEADER.pack(
            MAGIC, VERSION, env.type, env.flags, env.msg_id, env.dest,
            env.hops, env.max_hops, env.created_at, env.expires_at, len(env.body),
        )
    )
    out += env.body
    trace = [_label_bytes(t) for t in env.trace[:MAX_TRACE]]
    out.append(len(trace))
    for t in trace:
        out.append(len(t))
        out += t
    return bytes(out)


def decode(data: bytes) -> Envelope:
    if len(data) < HEADER_SIZE + 1:
        raise ProtocolError("too short")
    magic, version, typ, flags, msg_id, dest, hops, max_hops, created, expires, body_len = _HEADER.unpack_from(data)
    if magic != MAGIC:
        raise ProtocolError("bad magic")
    if version != VERSION:
        raise ProtocolError(f"unsupported version {version}")
    if typ not in TYPE_NAMES:
        raise ProtocolError("bad type")
    pos = HEADER_SIZE
    if body_len > MAX_BODY or len(data) < pos + body_len + 1:
        raise ProtocolError("bad body length")
    body = bytes(data[pos:pos + body_len])
    pos += body_len
    count = data[pos]
    pos += 1
    if count > MAX_TRACE:
        raise ProtocolError("trace too long")
    trace = []
    for _ in range(count):
        if pos >= len(data):
            raise ProtocolError("truncated trace")
        n = data[pos]
        pos += 1
        if n > MAX_LABEL_BYTES or pos + n > len(data):
            raise ProtocolError("bad trace entry")
        trace.append(data[pos:pos + n].decode("utf-8", errors="replace"))
        pos += n
    return Envelope(typ, flags, bytes(msg_id), bytes(dest), hops, max_hops, created, expires, body, trace)
