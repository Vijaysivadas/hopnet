"""Nearest hospitals, shelters, pharmacies... from OpenStreetMap. Public Overpass servers are often
slow, so the query goes to several mirrors at once and the first good answer wins; Nominatim is the
fallback. Each place carries its coordinates so the phone can point at it as the person walks."""

from __future__ import annotations

import asyncio

from .card import Card, Item
from .env import Env, Query
from .fetch import FetchError
from .geo import Place, locate
from .text import bearing, distance_km, fmt_distance

OVERPASS_MIRRORS = (
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
NOMINATIM_SEARCH = "https://nominatim.openstreetmap.org/search"
OVERPASS_HEAD_START_S = 8

# key: (title, Overpass selectors, Nominatim search word)
CATEGORIES = {
    "hospital": ("Hospitals & clinics", ['nwr["amenity"~"^(hospital|clinic)$"]', 'nwr["healthcare"="hospital"]'], "hospital"),
    "pharmacy": ("Pharmacies", ['nwr["amenity"="pharmacy"]', 'nwr["healthcare"="pharmacy"]'], "pharmacy"),
    # In Kerala relief camps open in schools and community halls; plain amenity=shelter is mostly bus stops.
    "shelter": ("Shelters & relief camps",
                ['nwr["emergency"="assembly_point"]', 'nwr["amenity"~"^(community_centre|townhall)$"]',
                 'nwr["social_facility"="shelter"]', 'nwr["amenity"="school"]'], "school"),
    "police": ("Police stations", ['nwr["amenity"="police"]'], "police"),
    "fire": ("Fire & rescue", ['nwr["amenity"="fire_station"]'], "fire station"),
    "fuel": ("Fuel stations", ['nwr["amenity"="fuel"]'], "fuel"),
    "atm": ("ATMs & banks", ['nwr["amenity"~"^(atm|bank)$"]'], "atm"),
    "water": ("Drinking water", ['nwr["amenity"="drinking_water"]', 'nwr["man_made"="water_tap"]'], "drinking water"),
    "food": ("Food & groceries", ['nwr["shop"~"^(supermarket|convenience|grocery|greengrocer)$"]',
                                  'nwr["amenity"="marketplace"]'], "supermarket"),
}

# What Nominatim may return for each category (its keyword search also matches names, e.g. "Hospital Road").
NOMINATIM_TYPES = {
    "hospital": {"hospital", "clinic", "doctors"}, "pharmacy": {"pharmacy", "chemist"},
    "shelter": {"school", "community_centre", "townhall", "assembly_point", "social_facility", "college"},
    "police": {"police"}, "fire": {"fire_station"}, "fuel": {"fuel"}, "atm": {"atm", "bank"},
    "water": {"drinking_water", "water_tap", "water_point"},
    "food": {"supermarket", "convenience", "grocery", "greengrocer", "marketplace"},
}

KIND = {
    "hospital": "Hospital", "clinic": "Clinic", "pharmacy": "Pharmacy", "assembly_point": "Assembly point",
    "shelter": "Shelter", "community_centre": "Community hall", "townhall": "Town hall",
    "school": "School · often a relief camp", "police": "Police", "fire_station": "Fire station", "fuel": "Fuel",
    "atm": "ATM", "bank": "Bank", "drinking_water": "Drinking water", "water_tap": "Water tap",
    "supermarket": "Supermarket", "convenience": "Shop", "grocery": "Grocery", "greengrocer": "Vegetables",
    "marketplace": "Market",
}


async def nearby(env: Env, q: Query, place: Place | None = None) -> Card:
    cat = q.cat if q.cat in CATEGORIES else "hospital"
    title, selectors, word = CATEGORIES[cat]
    place = place or await locate(env, q)
    found, source = await _search(env, selectors, word, place, NOMINATIM_TYPES[cat])
    for p in found:
        p["km"] = distance_km(place.lat, place.lon, p["lat"], p["lon"])
    found.sort(key=lambda p: p["km"])
    items = [_item(p, place) for p in found[:6]]
    body = ("Nearest first. After a disaster places may be closed or damaged: call ahead if you can."
            if items else f"No {title.lower()} found within 15 km on OpenStreetMap.")
    return Card(tool="nearby", title=f"{title} · {place.name}", body=body, items=items, src=source)


def _item(p: dict, place: Place) -> Item:
    tags = p["tags"]
    kind = KIND.get(tags.get("amenity") or tags.get("emergency") or tags.get("shop") or tags.get("man_made")
                    or tags.get("healthcare") or "", "")
    detail = [kind] if kind else []
    if tags.get("emergency") == "yes" or tags.get("opening_hours") == "24/7":
        detail.append("Open 24/7" + (" · emergency" if tags.get("emergency") == "yes" else ""))
    street = ", ".join(x for x in (tags.get("addr:street"), tags.get("addr:city") or tags.get("addr:place")) if x)
    if street:
        detail.append(street)
    phone = tags.get("phone") or tags.get("contact:phone") or tags.get("emergency:phone")
    meta = f"{fmt_distance(p['km'])} {bearing(place.lat, place.lon, p['lat'], p['lon'])}"
    if phone:
        meta += f" · ☎ {phone.split(';')[0].strip()}"
    name = tags.get("name:en") or tags.get("name") or (kind.split(" ·")[0] if kind else "Unnamed place")
    return Item(t=name, s=" · ".join(detail), m=meta, lat=p["lat"], lon=p["lon"])


async def _search(env: Env, selectors: list[str], word: str, place: Place, types: set[str]) -> tuple[list[dict], str]:
    """Overpass has the richest tags (phone, 24/7, emergency) but public servers can take half a
    minute. If it hasn't answered in a few seconds, Nominatim races it and the first answer wins."""
    overpass = asyncio.create_task(_overpass(env, selectors, place.lat, place.lon, 5000))
    done, _ = await asyncio.wait({overpass}, timeout=OVERPASS_HEAD_START_S)
    if done and not overpass.exception():
        found = overpass.result()
        if len(found) < 3:  # countryside: look further before giving up
            try:
                found = await _overpass(env, selectors, place.lat, place.lon, 15000) or found
            except FetchError:
                pass
        return found, "OpenStreetMap"

    nominatim = asyncio.create_task(_nominatim(env, word, place.lat, place.lon, types))
    sources = {overpass: "OpenStreetMap", nominatim: "OpenStreetMap (Nominatim)"}
    pending = {t for t in (overpass, nominatim) if not t.done() or not t.exception()}
    try:
        while pending:
            done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                if not task.exception() and task.result():
                    return task.result(), sources[task]
        # Both finished: an empty answer beats an error.
        for task in (overpass, nominatim):
            if not task.exception():
                return task.result(), sources[task]
        raise FetchError("The map services are busy. Try again in a minute.")
    finally:
        for task in (overpass, nominatim):
            task.cancel()


async def _overpass(env: Env, selectors: list[str], lat: float, lon: float, radius: int) -> list[dict]:
    lat, lon = round(lat, 3), round(lon, 3)  # ~100 m: neighbours share the cached answer
    around = f"(around:{radius},{lat},{lon})"
    query = f"[out:json][timeout:20];({''.join(s + around + ';' for s in selectors)});out center tags 150;"

    async def one(url: str):
        f = await env.fetcher.get(url, method="POST", form={"data": query}, ttl=6 * 3600, timeout=35)
        if f.status != 200:
            raise FetchError("map server busy")
        data = f.json()
        if not isinstance(data, dict) or "elements" not in data:
            raise FetchError("map server busy")
        return f, data["elements"]

    tasks = [asyncio.create_task(one(u)) for u in OVERPASS_MIRRORS]
    try:
        for next_done in asyncio.as_completed(tasks):
            try:
                f, elements = await next_done
            except FetchError:
                continue
            env.meter.add(f)
            places = []
            for e in elements:
                c = e.get("center") or e
                if "lat" in c and "lon" in c:
                    places.append({"lat": float(c["lat"]), "lon": float(c["lon"]), "tags": e.get("tags") or {}})
            return places
    finally:
        for t in tasks:
            t.cancel()
    raise FetchError("The map servers are busy. Try again in a minute.")


async def _nominatim(env: Env, word: str, lat: float, lon: float, types: set[str]) -> list[dict]:
    d = 0.08  # ~9 km box
    f = await env.fetcher.get(NOMINATIM_SEARCH, params={
        "q": word, "format": "jsonv2", "limit": 15, "bounded": 1, "extratags": 1, "accept-language": "en",
        "viewbox": f"{lon - d:.4f},{lat + d:.4f},{lon + d:.4f},{lat - d:.4f}",
    }, ttl=6 * 3600, timeout=12, meter=env.meter)
    rows = f.json()
    if not isinstance(rows, list):
        raise FetchError("The map service didn't answer")
    typed = [r for r in rows if r.get("type") in types]
    places = []
    for r in typed or rows:
        tags = dict(r.get("extratags") or {})
        tags.setdefault("name", r.get("name") or "")
        tags.setdefault(r.get("category") or "amenity", r.get("type") or "")
        places.append({"lat": float(r["lat"]), "lon": float(r["lon"]), "tags": tags})
    return places
