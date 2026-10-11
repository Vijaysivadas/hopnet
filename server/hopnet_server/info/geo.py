"""Where the person is: their GPS fix (GPS needs no internet) or a place name they typed."""

from __future__ import annotations

from dataclasses import dataclass

from .env import Env, Query
from .fetch import FetchError

NOMINATIM_REVERSE = "https://nominatim.openstreetmap.org/reverse"
NOMINATIM_SEARCH = "https://nominatim.openstreetmap.org/search"
GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"


@dataclass
class Place:
    lat: float
    lon: float
    name: str
    from_gps: bool


async def locate(env: Env, q: Query) -> Place:
    if q.lat is not None and q.lon is not None:
        name = await reverse(env, q.lat, q.lon)
        return Place(q.lat, q.lon, name or f"{q.lat:.3f}, {q.lon:.3f}", True)
    if q.place:
        f = await env.fetcher.get(GEOCODE, params={"name": q.place, "count": 1, "language": "en", "format": "json"},
                                  ttl=7 * 86400, meter=env.meter)
        hits = (f.json() or {}).get("results") or []
        if hits:
            h = hits[0]
            region = h.get("admin1") or h.get("country") or ""
            name = h.get("name", q.place) + (f", {region}" if region and region != h.get("name") else "")
            return Place(float(h["latitude"]), float(h["longitude"]), name, False)
        # Open-Meteo knows towns; districts, villages and landmarks ("Wayanad", "Sabarimala") need OSM.
        f = await env.fetcher.get(NOMINATIM_SEARCH, params={
            "q": q.place, "format": "jsonv2", "limit": 1, "addressdetails": 1, "accept-language": "en",
        }, ttl=7 * 86400, timeout=10, meter=env.meter)
        rows = f.json() if f.status == 200 else []
        if not rows:
            raise FetchError(f"Couldn't find a place called “{q.place}”")
        r = rows[0]
        state = (r.get("address") or {}).get("state")
        name = (r.get("name") or q.place) + (f", {state}" if state and state != r.get("name") else "")
        return Place(float(r["lat"]), float(r["lon"]), name, False)
    raise FetchError("Turn on location, or type a place name")


async def reverse(env: Env, lat: float, lon: float) -> str | None:
    """Town and state for a fix, e.g. "Aluva, Kerala". Rounded to ~100 m so neighbours share the cache;
    a failure only costs the name, never the answer."""
    try:
        f = await env.fetcher.get(NOMINATIM_REVERSE, params={
            "lat": round(lat, 3), "lon": round(lon, 3), "format": "jsonv2", "zoom": 14, "accept-language": "en",
        }, ttl=7 * 86400, timeout=8, meter=env.meter)
        address = (f.json() or {}).get("address") or {}
    except FetchError:
        return None
    local = next((address[k] for k in ("town", "city", "village", "suburb", "hamlet", "municipality", "county")
                  if address.get(k)), None)
    region = address.get("state") or address.get("state_district") or address.get("country")
    if local and region and local != region:
        return f"{local}, {region}"
    return local or region
