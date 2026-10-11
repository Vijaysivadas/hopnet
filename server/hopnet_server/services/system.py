from __future__ import annotations

from .base import ServiceHandler


class SystemService(ServiceHandler):
    """Duties every app performs automatically through the SDK.

    sys/poll  "I'm here": the hub already subscribed the carrying gateway to the sender and
              pushed their pending mail before this runs, so there is nothing else to do.
    sys/ack   {"ids": [...], "trips": [{"id", "path", "hops"}]} the sender received these DOWN
              messages; trips report the path each one took to reach the phone (for the debug page).
    """

    id = "sys"

    async def handle(self, ctx, sender, request):
        kind = request.get("kind")
        data = request.get("data", {})
        if kind == "poll":
            ctx.annotate(summary="poll · I'm here, send my mail")
        elif kind == "ack":
            ids = data.get("ids", [])
            if not isinstance(ids, list):
                return
            ctx.annotate(summary=f"ack · received {len(ids)} message{'' if len(ids) == 1 else 's'}")
            await ctx.mark_delivered(sender.address, [str(i) for i in ids], _parse_trips(data.get("trips")))


def _parse_trips(raw) -> dict[str, tuple[str, int]]:
    trips: dict[str, tuple[str, int]] = {}
    if not isinstance(raw, list):
        return trips
    for item in raw[:200]:
        if not isinstance(item, dict):
            continue
        try:
            hops = max(0, min(int(item.get("hops", 0)), 255))
        except (TypeError, ValueError):
            hops = 0
        trips[str(item.get("id", ""))] = (str(item.get("path", ""))[:300], hops)
    return trips
