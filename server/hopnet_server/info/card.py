"""The answer card: what a Live Info reply looks like on the wire, and how it is squeezed into one
envelope.

A DOWN envelope body is at most 4096 bytes: the sealed box adds 48, the signature and length 66,
and the plaintext is padded to a multiple of 256, so the inner JSON must stay under ~3,770 bytes.
The inner JSON wraps `data` with about 160 bytes of ids and times, so a card gets 3,400.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

MAX_DATA_BYTES = 3400

LEVELS = ("info", "warn", "danger")


@dataclass
class Item:
    t: str                      # title line
    s: str = ""                 # detail
    m: str = ""                 # meta: source · time · distance
    k: str | None = None        # link key: the phone sends it back to read the page
    lvl: str | None = None      # "warn" | "danger"
    lat: float | None = None    # places: lets the phone point at it as the person moves
    lon: float | None = None

    def to_dict(self) -> dict:
        d = {"t": self.t}
        for key in ("s", "m", "k", "lvl"):
            if getattr(self, key):
                d[key] = getattr(self, key)
        if self.lat is not None and self.lon is not None:
            d["lat"], d["lon"] = round(self.lat, 5), round(self.lon, 5)
        return d


@dataclass
class Card:
    tool: str
    title: str
    body: str = ""
    items: list[Item] = field(default_factory=list)
    src: str = ""
    url: str | None = None
    level: str = "info"         # info | warn | danger
    ok: bool = True
    ai: bool = False
    part: int | None = None     # read: which part of the page this is
    parts: int | None = None
    key: str | None = None      # read: link key for the next part

    def to_data(self, ref: str | None, at: int, fetched: int, cached: bool) -> dict:
        d: dict = {"ref": ref, "tool": self.tool, "ok": self.ok, "title": self.title}
        if self.body:
            d["body"] = self.body
        if self.items:
            d["items"] = [i.to_dict() for i in self.items]
        if self.src:
            d["src"] = self.src
        if self.url:
            d["url"] = self.url
        if self.level != "info":
            d["lvl"] = self.level
        if self.ai:
            d["ai"] = True
        if self.part is not None:
            d["part"], d["parts"] = self.part, self.parts
        if self.key:
            d["key"] = self.key
        d["at"] = at
        d["bytes"] = fetched
        if cached:
            d["cached"] = True
        if ref is None:
            del d["ref"]
        return d


def error_card(tool: str, title: str, body: str) -> Card:
    return Card(tool=tool, title=title, body=body, ok=False, level="warn")


def worst(levels) -> str:
    best = 0
    for lvl in levels:
        if lvl in LEVELS:
            best = max(best, LEVELS.index(lvl))
    return LEVELS[best]


def size_of(data: dict) -> int:
    return len(json.dumps(data, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def cut_bytes(text: str, limit: int, ellipsis: str = "…") -> str:
    """Shortens [text] to at most [limit] UTF-8 bytes, on a word boundary when one is close."""
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text
    room = max(0, limit - len(ellipsis.encode("utf-8")))
    cut = raw[:room].decode("utf-8", errors="ignore")
    space = cut.rfind(" ")
    if space > len(cut) * 0.7:
        cut = cut[:space]
    return cut.rstrip(" ,;:-·") + ellipsis


def fit(data: dict, limit: int = MAX_DATA_BYTES) -> dict:
    """Trims a card until it fits one envelope: long item details first, then the last items,
    then the body. Malayalam and Hindi take 3 bytes a character, so this counts bytes."""
    data = json.loads(json.dumps(data, ensure_ascii=False))
    for _ in range(200):
        over = size_of(data) - limit
        if over <= 0:
            return data
        items = data.get("items") or []
        longest = max(items, key=lambda i: len(i.get("s", "").encode("utf-8")), default=None)
        if longest is not None and len(longest.get("s", "").encode("utf-8")) > 90:
            s = longest["s"]
            longest["s"] = cut_bytes(s, max(80, len(s.encode("utf-8")) - over - 8))
        elif len(items) > 2:
            items.pop()
        elif len(data.get("body", "").encode("utf-8")) > 120:
            body = data["body"]
            data["body"] = cut_bytes(body, max(100, len(body.encode("utf-8")) - over - 8))
        elif items:
            items.pop()
        else:
            data["title"] = cut_bytes(data.get("title", ""), 60)
            data.pop("url", None)
            if size_of(data) > limit:
                data["body"] = cut_bytes(data.get("body", ""), 100)
    return data
