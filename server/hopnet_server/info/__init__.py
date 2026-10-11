"""Live Info: the server reads the internet so offline phones don't have to.

A phone sends a ~200-byte question through the mesh; the server fetches what it needs (weather,
official alerts, maps, news, any web page), keeps only what matters and sends back one signed card
of at most ~3.4 KB. The server spends the megabytes; the mesh carries kilobytes.
"""

from __future__ import annotations

import asyncio

from .alerts import alerts, survey
from .card import Card, Item, worst
from .env import Env, Query
from .fetch import FetchError
from .geo import locate
from .news import news
from .places import nearby
from .weather import analyse, forecast, weather
from .web import ask, read, search, wiki

LABELS = {
    "brief": "the briefing", "weather": "the weather", "alerts": "alerts", "nearby": "nearby places",
    "news": "the news", "wiki": "Wikipedia", "search": "web results", "ask": "an answer", "read": "that page",
}


async def run_tool(env: Env, q: Query) -> Card:
    tools = {"brief": brief, "weather": weather, "alerts": alerts, "nearby": nearby, "news": news,
             "wiki": wiki, "search": search, "ask": ask, "read": read}
    return await tools[q.tool](env, q)


async def brief(env: Env, q: Query) -> Card:
    """Everything that matters for where you are, in one reply: the cheapest thing to send over a mesh."""
    place = await locate(env, q)
    topic = "kerala" if place.name.endswith("Kerala") else "top"
    wx, warn, heads = await asyncio.gather(
        forecast(env, place.lat, place.lon),
        survey(env, place.lat, place.lon),
        news(env, Query(tool="news", topic=topic), limit=3),
        return_exceptions=True,
    )
    lines: list[str] = []
    items: list[Item] = []
    levels: list[str] = []
    answered: list[str] = []
    if isinstance(warn, BaseException):
        lines.append("Alerts: couldn't reach the alert services.")
    else:
        answered.append("NDMA SACHET")
        official = [a for a in warn.here if a.official]
        lines.append(f"⚠ {len(official)} official warning{'s' if len(official) != 1 else ''} for your area"
                     if official else "No official warnings for your area.")
        items += [a.item() for a in warn.here[:3]]
        levels += [a.level for a in warn.here]
    if isinstance(wx, BaseException):
        lines.append("Weather: the forecast service didn't answer.")
    else:
        answered.append("Open-Meteo")
        lines += analyse(wx).now
    if not isinstance(heads, BaseException):
        answered.append(heads.src)
        items += heads.items
    if not answered:
        raise FetchError("None of the services answered. Try again in a minute.")
    return Card(tool="brief", title=f"Briefing · {place.name}", body="\n".join(lines), items=items,
                src=" · ".join(answered), level=worst(levels))


__all__ = ["Card", "Env", "FetchError", "LABELS", "Query", "run_tool", "survey"]
