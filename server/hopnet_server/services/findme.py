from __future__ import annotations

import math
import re

from ..db import FindMeReport
from .base import ServiceHandler

KINDS = {"lost", "found"}
CASE_RE = re.compile(r"^[0-9a-f]{8,32}$")
MAX_NOTE = 200
MAX_NAME = 40


class FindMeService(ServiceHandler):
    """Find Me: someone lost in a crowd tells every phone nearby, and the police, where they are.

    Phones send it as a BROADCAST: signed, readable by every phone, delivered with no server at
    all. Any gateway that carries one also uploads it here, and /police shows it.

    broadcast findme/lost  {case, name, handle, lat, lon, acc, fixAt, note, battery}
    broadcast findme/found {case, name, handle, lat, lon, acc, fixAt, battery}
    """

    id = "findme"
    accepts_broadcasts = True

    async def handle(self, ctx, sender, request):
        ctx.annotate(note="Find Me is sent as a broadcast, not a sealed request")

    async def handle_broadcast(self, ctx, address, sender, request, envelope):
        kind = request.get("kind")
        data = request.get("data") or {}
        case_id = str(data.get("case", "")).lower()
        if kind not in KINDS or not CASE_RE.match(case_id):
            ctx.annotate(note="malformed Find Me report")
            return

        lat, lon = _number(data.get("lat"), -90, 90), _number(data.get("lon"), -180, 180)
        has_fix = lat is not None and lon is not None
        battery = _int(data.get("battery"))
        note = str(data.get("note") or "").strip()[:MAX_NOTE] or None
        # A registered user's name comes from registration, not from what the phone claims.
        name = sender.name if sender else (str(data.get("name") or "").strip()[:MAX_NAME] or "Unknown")
        report = FindMeReport(
            address=address,
            case_id=case_id,
            handle=sender.handle if sender else None,
            name=name,
            status=kind,
            lat=lat if has_fix else None,
            lon=lon if has_fix else None,
            acc=_number(data.get("acc"), 0, 100_000) if has_fix else None,
            fix_at=_int(data.get("fixAt")) if has_fix else None,
            note=note,
            battery=battery if battery is not None and 0 <= battery <= 100 else None,
            sent_at=request["ts"],
            hops=envelope.hops,
            path=">".join(envelope.trace),
            gateway=ctx.session.label if ctx.session else None,
        )
        latest = ctx.hub.db.findme_report(report, ctx.now())

        where = f"{lat:.5f}, {lon:.5f}" + (f" ±{report.acc:.0f} m" if report.acc is not None else "") if has_fix else "no GPS fix yet"
        who = name + (f" (@{sender.handle})" if sender else " (unregistered)")
        ctx.annotate(summary=f"{kind.upper()} · {who} · {where}", note=None if latest else "older update")
        if latest:
            ctx.event("findme", f"🆘 {who} is lost · {where}" if kind == "lost" else f"✅ {who} is found")


def _number(value, low: float, high: float) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) and low <= value <= high else None


def _int(value) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None
