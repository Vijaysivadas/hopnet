"""Weather from Open-Meteo (free, no key), plus warnings derived from the forecast with IMD's rainfall
categories. Derived warnings are labelled as forecasts: official ones come from the alerts tool."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

from .card import Card, Item, worst
from .env import Env, Query
from .fetch import FetchError
from .geo import Place, locate

FORECAST = "https://api.open-meteo.com/v1/forecast"

WMO = {
    0: "Clear sky", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Fog",
    51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 56: "Freezing drizzle", 57: "Freezing drizzle",
    61: "Light rain", 63: "Rain", 65: "Heavy rain", 66: "Freezing rain", 67: "Freezing rain",
    71: "Light snow", 73: "Snow", 75: "Heavy snow", 77: "Snow grains",
    80: "Light showers", 81: "Showers", 82: "Violent showers", 85: "Snow showers", 86: "Heavy snow showers",
    95: "Thunderstorm", 96: "Thunderstorm with hail", 99: "Thunderstorm with heavy hail",
}

# IMD 24-hour rainfall categories (mm).
HEAVY, VERY_HEAVY, EXTREME = 64.5, 115.6, 204.5


@dataclass
class Warning:
    key: str      # stable id for "is this new?" (watches)
    level: str    # warn | danger
    title: str
    advice: str


@dataclass
class Outlook:
    now: list[str]
    days: list[Item]
    warnings: list[Warning]
    rain_24h: float


async def forecast(env: Env, lat: float, lon: float) -> dict:
    f = await env.fetcher.get(FORECAST, params={
        "latitude": round(lat, 2), "longitude": round(lon, 2), "timezone": "auto", "forecast_days": 3,
        "current": "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,"
                   "wind_speed_10m,wind_gusts_10m",
        "hourly": "precipitation,precipitation_probability,weather_code,wind_gusts_10m",
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,"
                 "precipitation_probability_max,wind_gusts_10m_max,apparent_temperature_max",
    }, ttl=900, meter=env.meter)
    data = f.json()
    if f.status != 200 or not isinstance(data, dict) or "current" not in data:
        raise FetchError("The weather service didn't answer")
    return data


def analyse(data: dict) -> Outlook:
    cur = data["current"]
    hourly = data.get("hourly") or {}
    times: list[str] = hourly.get("time") or []
    start = next((i for i, t in enumerate(times) if t >= cur["time"][:13]), 0)
    window = slice(start, start + 24)
    rain = [x or 0.0 for x in (hourly.get("precipitation") or [])[window]]
    chance = [x or 0 for x in (hourly.get("precipitation_probability") or [])[window]]
    codes = [x or 0 for x in (hourly.get("weather_code") or [])[window]]
    gusts = [x or 0.0 for x in (hourly.get("wind_gusts_10m") or [])[window]]
    hours = times[window]
    rain_24h = round(sum(rain), 1)

    now = [
        f"Now {cur['temperature_2m']:.0f}°C, {WMO.get(cur['weather_code'], 'weather unknown').lower()} · "
        f"feels like {cur['apparent_temperature']:.0f}°C",
        f"Humidity {cur['relative_humidity_2m']:.0f}% · wind {cur['wind_speed_10m']:.0f} km/h, "
        f"gusts {cur['wind_gusts_10m']:.0f} km/h",
    ]
    if rain_24h < 1:
        now.append("Next 24 h: no real rain expected")
    else:
        best = max(range(max(1, len(rain) - 2)), key=lambda i: sum(rain[i:i + 3]))
        peak = f", heaviest {_hour(hours[best])}–{_hour(hours[min(best + 3, len(hours) - 1)])}" if hours else ""
        now.append(f"Next 24 h: {rain_24h:.0f} mm rain{peak} (up to {max(chance, default=0)}% chance)")

    warnings: list[Warning] = []
    today = cur["time"][:10]
    if rain_24h >= EXTREME:
        warnings.append(Warning(f"wx:rain3:{today}", "danger", f"Extremely heavy rain: {rain_24h:.0f} mm in 24 h",
                                "Flooding and landslides likely. Move to higher ground if you are near a river or slope."))
    elif rain_24h >= VERY_HEAVY:
        warnings.append(Warning(f"wx:rain2:{today}", "danger", f"Very heavy rain: {rain_24h:.0f} mm in 24 h",
                                "Avoid rivers, streams and low roads. Keep phones charged and documents dry."))
    elif rain_24h >= HEAVY:
        warnings.append(Warning(f"wx:rain1:{today}", "warn", f"Heavy rain: {rain_24h:.0f} mm in 24 h",
                                "Avoid low-lying roads and riverbanks."))
    gust = max(gusts, default=0)
    if gust >= 89:
        warnings.append(Warning(f"wx:wind2:{today}", "danger", f"Storm-force gusts up to {gust:.0f} km/h",
                                "Stay indoors, away from windows, trees and power lines."))
    elif gust >= 62:
        warnings.append(Warning(f"wx:wind1:{today}", "warn", f"Strong gusts up to {gust:.0f} km/h",
                                "Secure loose objects; avoid trees and hoardings."))
    if any(c >= 95 for c in codes[:12]):
        warnings.append(Warning(f"wx:storm:{today}", "warn", "Thunderstorms likely in the next 12 h",
                                "Stay indoors; avoid open fields, water and tall trees."))
    feels = max((x or 0 for x in (data.get("daily") or {}).get("apparent_temperature_max", [])[:1]), default=0)
    if feels >= 41:
        warnings.append(Warning(f"wx:heat:{today}", "danger" if feels >= 45 else "warn",
                                f"Extreme heat: feels like {feels:.0f}°C",
                                "Drink water, stay in shade 12–4 PM, check on elderly people."))

    days: list[Item] = []
    daily = data.get("daily") or {}
    for i, day in enumerate((daily.get("time") or [])[:3]):
        mm = (daily.get("precipitation_sum") or [0] * 3)[i] or 0
        pct = (daily.get("precipitation_probability_max") or [0] * 3)[i] or 0
        code = (daily.get("weather_code") or [0] * 3)[i]
        lo = (daily.get("temperature_2m_min") or [0] * 3)[i]
        hi = (daily.get("temperature_2m_max") or [0] * 3)[i]
        days.append(Item(
            t=_day_name(day, today),
            s=f"{WMO.get(code, '—')} · {lo:.0f}–{hi:.0f}°C",
            m=f"{mm:.0f} mm rain · {pct}%",
            lvl="danger" if mm >= VERY_HEAVY else "warn" if mm >= HEAVY else None,
        ))
    return Outlook(now, days, warnings, rain_24h)


async def weather(env: Env, q: Query, place: Place | None = None) -> Card:
    place = place or await locate(env, q)
    outlook = analyse(await forecast(env, place.lat, place.lon))
    lines = [f"⚠ {w.title}. {w.advice}" for w in outlook.warnings] + outlook.now
    return Card(
        tool="weather",
        title=f"Weather · {place.name}",
        body="\n".join(lines),
        items=outlook.days,
        src="Open-Meteo forecast",
        level=worst(w.level for w in outlook.warnings),
    )


def _hour(iso: str) -> str:
    h = int(iso[11:13])
    return f"{(h % 12) or 12} {'AM' if h < 12 else 'PM'}"


def _day_name(day: str, today: str) -> str:
    d, t = date.fromisoformat(day), date.fromisoformat(today)
    if d == t:
        return "Today"
    if (d - t).days == 1:
        return "Tomorrow"
    return datetime(d.year, d.month, d.day).strftime("%A")
