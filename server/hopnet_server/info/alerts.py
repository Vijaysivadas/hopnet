"""Warnings for a location, official sources first:
  * NDMA SACHET: India's Common Alerting Protocol feed (IMD, CWC, state disaster authorities)
  * USGS: earthquakes in the last day
  * GDACS: UN/EU global disaster alerts (cyclones, floods, earthquakes)
  * the forecast itself (heavy rain, wind, thunder, heat), clearly marked as a forecast
Each source may fail on its own; the card says which ones answered."""

from __future__ import annotations

import asyncio
import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .card import Card, Item, worst
from .env import Env, Query
from .fetch import FetchError
from .geo import Place, locate
from .text import ago, bearing, distance_km, fmt_distance, parse_feed
from .weather import analyse, forecast

SACHET = "https://sachet.ndma.gov.in/cap_public_website/FetchAllAlertDetails"
USGS = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson"
GDACS = "https://www.gdacs.org/xml/rss.xml"

RANK = {"danger": 2, "warn": 1, "info": 0}
IST = timezone(timedelta(hours=5, minutes=30))  # SACHET times are Indian


@dataclass
class Alert:
    id: str
    level: str
    title: str
    detail: str
    meta: str
    km: float
    official: bool

    def item(self) -> Item:
        return Item(t=self.title, s=self.detail, m=self.meta, lvl=self.level if self.level != "info" else None)


@dataclass
class Survey:
    here: list[Alert]
    elsewhere: int          # official alerts active in India but not here
    answered: list[str]     # sources that answered
    failed: list[str]


async def survey(env: Env, lat: float, lon: float) -> Survey:
    now = env.now()
    jobs = {
        "NDMA SACHET": _sachet(env, lat, lon, now),
        "USGS": _usgs(env, lat, lon, now),
        "GDACS": _gdacs(env, lat, lon),
        "forecast": _forecast(env, lat, lon),
    }
    results = await asyncio.gather(*jobs.values(), return_exceptions=True)
    here: list[Alert] = []
    elsewhere = 0
    answered, failed = [], []
    for name, result in zip(jobs, results):
        if isinstance(result, BaseException):
            if not isinstance(result, (FetchError, ValueError, KeyError, TypeError)):
                raise result
            failed.append(name)
            continue
        answered.append(name)
        found, other = result
        here += found
        elsewhere += other
    if not answered:
        raise FetchError("None of the alert services answered")
    here.sort(key=lambda a: (not a.official, -RANK[a.level], a.km))
    return Survey(here, elsewhere, answered, failed)


async def alerts(env: Env, q: Query, place: Place | None = None) -> Card:
    place = place or await locate(env, q)
    s = await survey(env, place.lat, place.lon)
    if s.here:
        official = sum(1 for a in s.here if a.official)
        body = (f"{official} official warning{'s' if official != 1 else ''} for your area"
                if official else "No official warnings for your area. Forecast-based notes below.")
    else:
        body = "No warnings for your area right now."
    if s.elsewhere:
        body += f"\n{s.elsewhere} other official alert{'s' if s.elsewhere != 1 else ''} active elsewhere in India."
    if s.failed:
        body += f"\nCouldn't reach: {', '.join(s.failed)}."
    return Card(
        tool="alerts",
        title=f"Alerts · {place.name}",
        body=body,
        items=[a.item() for a in s.here[:6]],
        src=" · ".join(s.answered),
        level=worst(a.level for a in s.here),
    )


# ---- sources ----------------------------------------------------------------------------

async def _sachet(env: Env, lat: float, lon: float, now: int) -> tuple[list[Alert], int]:
    f = await env.fetcher.get(SACHET, ttl=120, meter=env.meter, timeout=12)
    _check(f, "SACHET")
    rows = f.json()
    if not isinstance(rows, list):
        raise FetchError("SACHET sent an unexpected answer")
    here, other = [], 0
    for r in rows:
        try:
            c_lon, c_lat = (float(x) for x in str(r.get("centroid", "")).split(",")[:2])
        except ValueError:
            continue
        ends = _sachet_time(r.get("effective_end_time"))
        if ends and ends < now:
            continue
        try:
            area = float(r.get("area_covered") or 0)
        except ValueError:
            area = 0.0
        radius = max(10.0, math.sqrt(max(area, 0) / math.pi))
        km = distance_km(lat, lon, c_lat, c_lon)
        if km > radius + 25:
            other += 1
            continue
        color = str(r.get("severity_color", "")).lower()
        level = {"red": "danger", "orange": "warn", "yellow": "warn"}.get(color, "info")
        until = f" · until {datetime.fromtimestamp(ends / 1000, IST).strftime('%a %I:%M %p').replace(' 0', ' ')}" if ends else ""
        here.append(Alert(
            id=f"sachet:{r.get('identifier')}",
            level=level,
            title=f"{color.title() or 'Official'} alert · {str(r.get('disaster_type', 'Warning')).strip()}",
            detail=str(r.get("warning_message", "")).strip(),
            meta=f"{str(r.get('area_description', '')).strip()}{until} · {r.get('alert_source') or 'NDMA SACHET'}",
            km=0.0 if km <= radius else km - radius,
            official=True,
        ))
    return here, other


def _check(f, name: str) -> None:
    if f.status != 200:
        raise FetchError(f"{name} answered {f.status}")


def _sachet_time(value) -> int | None:
    """"Sun Oct 11 11:57:00 IST 2026" → epoch ms."""
    if not value:
        return None
    try:
        return int(datetime.strptime(str(value).replace("IST", "+0530"), "%a %b %d %H:%M:%S %z %Y").timestamp() * 1000)
    except ValueError:
        return None


async def _usgs(env: Env, lat: float, lon: float, now: int) -> tuple[list[Alert], int]:
    f = await env.fetcher.get(USGS, ttl=300, meter=env.meter, timeout=12)
    _check(f, "USGS")
    here = []
    for feat in (f.json() or {}).get("features", []):
        p, g = feat.get("properties") or {}, (feat.get("geometry") or {}).get("coordinates") or []
        if len(g) < 2 or p.get("mag") is None:
            continue
        mag = float(p["mag"])
        km = distance_km(lat, lon, g[1], g[0])
        if not (km <= 300 or (mag >= 6 and km <= 1000)):
            continue
        here.append(Alert(
            id=f"usgs:{feat.get('id')}",
            level="danger" if mag >= 6 else "warn" if mag >= 4.5 else "info",
            title=f"M{mag:.1f} earthquake",
            detail=str(p.get("place") or ""),
            meta=f"{fmt_distance(km)} {bearing(lat, lon, g[1], g[0])} · {ago(p.get('time'), now)} · USGS",
            km=km,
            official=True,
        ))
    return here, 0


async def _gdacs(env: Env, lat: float, lon: float) -> tuple[list[Alert], int]:
    f = await env.fetcher.get(GDACS, ttl=900, meter=env.meter, timeout=15, max_bytes=4_000_000)
    _check(f, "GDACS")
    here = []
    for item in parse_feed(f.body):
        x = item.extra
        if x.get("iscurrent", "true").lower() != "true":
            continue
        try:
            e_lat, e_lon = float(x.get("lat", "")), float(x.get("long", ""))
        except ValueError:
            continue
        level = x.get("alertlevel", "Green").lower()
        km = distance_km(lat, lon, e_lat, e_lon)
        if not (km <= 300 or (level in ("orange", "red") and km <= 1500)):
            continue
        here.append(Alert(
            id=f"gdacs:{x.get('guid') or item.link}",
            level={"red": "danger", "orange": "warn"}.get(level, "info"),
            title=item.title,
            detail=item.summary,
            meta=f"{fmt_distance(km)} {bearing(lat, lon, e_lat, e_lon)} · GDACS",
            km=km,
            official=True,
        ))
    return here, 0


async def _forecast(env: Env, lat: float, lon: float) -> tuple[list[Alert], int]:
    outlook = analyse(await forecast(env, lat, lon))
    return [Alert(id=w.key, level=w.level, title=w.title, detail=w.advice, meta="Forecast, not an official warning · Open-Meteo",
                  km=0.0, official=False) for w in outlook.warnings], 0
