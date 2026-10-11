"""What every Live Info tool receives: the parsed request and the means to answer it."""

from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable

from .fetch import Fetcher, Meter

if TYPE_CHECKING:
    from ..db import Database
    from .ai import AI

TOOLS = ("brief", "weather", "alerts", "nearby", "news", "wiki", "search", "ask", "read")
NEEDS_PLACE = {"brief", "weather", "alerts", "nearby"}
KEY_RE = re.compile(r"^[0-9a-f]{10}$")


@dataclass
class Query:
    tool: str
    lat: float | None = None
    lon: float | None = None
    place: str = ""
    q: str = ""
    topic: str = ""
    cat: str = ""
    url: str = ""
    key: str = ""
    part: int = 1
    lang: str = "en"

    def describe(self) -> str:
        """One line for the debug page and the event log."""
        where = self.place or (f"{self.lat:.3f},{self.lon:.3f}" if self.lat is not None else "")
        bits = {
            "brief": where, "weather": where, "alerts": where,
            "nearby": f"{self.cat or 'hospital'} near {where}",
            "news": f"“{self.q}”" if self.q else (self.topic or "top"),
            "wiki": self.q, "search": self.q, "ask": self.q,
            "read": f"{self.url or 'link ' + self.key} · part {self.part}",
        }
        return f"{self.tool} · {bits.get(self.tool, '')}".strip(" ·")


def parse_query(data: dict) -> Query:
    """Validates what a phone asked for. Raises ValueError with a message the person can read."""
    tool = str(data.get("tool", "")).strip().lower()
    if tool not in TOOLS:
        raise ValueError(f"Unknown request “{tool}”")
    lat, lon = _number(data.get("lat"), -90, 90), _number(data.get("lon"), -180, 180)
    if lat is None or lon is None:
        lat = lon = None
    part = data.get("part", 1)
    q = Query(
        tool=tool,
        lat=lat,
        lon=lon,
        place=_text(data.get("place"), 80),
        q=_text(data.get("q"), 300),
        topic=_text(data.get("topic"), 20).lower(),
        cat=_text(data.get("cat"), 20).lower(),
        url=_text(data.get("url"), 1000),
        key=_text(data.get("key"), 10).lower(),
        part=part if isinstance(part, int) and not isinstance(part, bool) and 1 <= part <= 100 else 1,
        lang=(_text(data.get("lang"), 2).lower() or "en"),
    )
    if tool in NEEDS_PLACE and q.lat is None and not q.place:
        raise ValueError("Turn on location, or type a place name")
    if tool in ("wiki", "search", "ask") and len(q.q) < 2:
        raise ValueError("Type a question first")
    if tool == "read" and not (q.url or KEY_RE.match(q.key)):
        raise ValueError("Which page? Send a link")
    return q


@dataclass
class Links:
    """Short keys for URLs: a news card says `k: "3f9a…"` instead of a 300-byte link, and the phone
    sends the key back when the person taps it to read the page."""
    db: "Database"
    now: Callable[[], int]

    def put(self, url: str, title: str = "") -> str:
        key = hashlib.sha256(url.encode("utf-8")).hexdigest()[:10]
        self.db.info_link_put(key, url, title[:200], self.now())
        return key

    def get(self, key: str) -> tuple[str, str] | None:
        return self.db.info_link_get(key)


@dataclass
class Env:
    fetcher: Fetcher
    links: Links
    now: Callable[[], int]
    ai: "AI | None" = None
    meter: Meter = field(default_factory=Meter)


def _number(value, low: float, high: float) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) and low <= value <= high else None


def _text(value, limit: int) -> str:
    if value is None or isinstance(value, (dict, list)):
        return ""
    return " ".join(str(value).split())[:limit]
