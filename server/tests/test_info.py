"""Live Info: questions in, small signed answer cards out. The internet is faked with httpx.MockTransport,
so these tests are fast and offline; tools/live_info.py runs the same tools against the real sources."""

import json
import time
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient

from hopnet_server import envelope as wire
from hopnet_server.app import create_app
from hopnet_server.crypto import RECEIVED
from hopnet_server.info.ai import AI
from hopnet_server.info.card import MAX_DATA_BYTES, Card, Item, fit, size_of
from hopnet_server.info.fetch import Fetcher, FetchError
from hopnet_server.info.text import html_to_text, parse_feed
from hopnet_server.info.web import split_parts
from hopnet_server.services.info import InfoService

from .phone import Phone, parse_receipt, receive_until, send_up

ALUVA = {"lat": 10.1076, "lon": 76.3516}
IST = timezone(timedelta(hours=5, minutes=30))


# ---- a fake internet ----------------------------------------------------------------------

def forecast_json(rain_per_hour: float = 5.0) -> dict:
    start = datetime(2026, 10, 11)
    hours = [(start + timedelta(hours=i)).strftime("%Y-%m-%dT%H:00") for i in range(72)]
    return {
        "current": {"time": "2026-10-11T09:00", "temperature_2m": 27.2, "relative_humidity_2m": 83,
                    "apparent_temperature": 32.4, "precipitation": 1.0, "weather_code": 63,
                    "wind_speed_10m": 6.3, "wind_gusts_10m": 16.9},
        "hourly": {"time": hours, "precipitation": [rain_per_hour] * 72, "precipitation_probability": [90] * 72,
                   "weather_code": [63] * 72, "wind_gusts_10m": [30.0] * 72},
        "daily": {"time": ["2026-10-11", "2026-10-12", "2026-10-13"], "weather_code": [65, 63, 3],
                  "temperature_2m_max": [29, 30, 31], "temperature_2m_min": [24, 24, 25],
                  "precipitation_sum": [120.0, 30.0, 0.0], "precipitation_probability_max": [95, 80, 10],
                  "wind_gusts_10m_max": [40, 35, 20], "apparent_temperature_max": [34, 35, 36]},
    }


def sachet_alert(identifier: int, lat: float, lon: float, color: str = "orange") -> dict:
    ends = (datetime.now(IST) + timedelta(days=1)).strftime("%a %b %d %H:%M:%S IST %Y")
    return {"identifier": identifier, "severity_color": color, "disaster_type": "Heavy Rain",
            "area_description": "Ernakulam district of Kerala", "warning_message": "Heavy rain likely. Stay alert.",
            "centroid": f"{lon},{lat}", "area_covered": "3000", "effective_end_time": ends, "alert_source": "IMD"}


RSS = """<?xml version="1.0"?><rss version="2.0"><channel><title>Kerala</title>
<item><title><![CDATA[Shutters of Idukki dam to be raised]]></title><link>https://news.example.in/idukki</link>
<description><![CDATA[<p>KSEB will raise two shutters at 2 p.m.</p>]]></description><pubDate>Sun, 11 Oct 2026 03:00:00 GMT</pubDate></item>
<item><title>Relief camps opened in Aluva</title><link>https://news.example.in/camps</link>
<description>Twelve camps are open.</description><pubDate>Sun, 11 Oct 2026 02:00:00 GMT</pubDate></item>
</channel></rss>"""

ARTICLE = """<html class="vector-toc-available"><head><title>Idukki dam | News</title></head><body>
<nav>Home | Kerala | Sign in</nav>
<article><h1>Shutters of Idukki dam to be raised</h1>
<p>The Kerala State Electricity Board will raise two shutters of the Idukki dam at 2 p.m. on Sunday as the water level crossed the red alert mark.<sup>[1]</sup></p>
<div class="share-bar">Share on WhatsApp Share on X</div>
<p>People living on the banks of the Periyar have been asked to move to relief camps. Twelve camps are open in Aluva taluk.</p>
""" + "".join(f"<p>Paragraph {i}: residents downstream should stay alert and follow official instructions from the district administration.</p>" for i in range(60)) + """
</article><footer>Copyright 2026</footer></body></html>"""

DDG_HTML = """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fcial.aero%2Fstatus&amp;rut=x">CIAL flight status</a>
<a class="result__snippet" href="#">Live arrivals and departures at Kochi airport.</a></div>
<div class="result result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad_provider=x">Cheap flights</a></div>"""


class FakeInternet:
    def __init__(self):
        self.calls: list[str] = []
        self.sachet = [sachet_alert(1, 10.11, 76.35)]
        self.rain_per_hour = 5.0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        host, path = request.url.host, request.url.path
        self.calls.append(f"{host}{path}")
        if host == "nominatim.openstreetmap.org" and path == "/reverse":
            return httpx.Response(200, json={"address": {"suburb": "Thottakkattukara", "town": "Aluva", "state": "Kerala"}})
        if host == "nominatim.openstreetmap.org" and path == "/search":
            return httpx.Response(200, json=[])
        if host == "api.open-meteo.com":
            return httpx.Response(200, json=forecast_json(self.rain_per_hour))
        if host == "geocoding-api.open-meteo.com":
            name = request.url.params.get("name")
            if name == "Nowhereville":
                return httpx.Response(200, json={})
            return httpx.Response(200, json={"results": [{"name": "Munnar", "admin1": "Kerala", "latitude": 10.09, "longitude": 77.06}]})
        if host == "sachet.ndma.gov.in":
            return httpx.Response(200, json=self.sachet)
        if host == "earthquake.usgs.gov":
            return httpx.Response(200, json={"features": []})
        if host == "www.gdacs.org":
            return httpx.Response(503)
        if host in ("overpass-api.de", "maps.mail.ru", "overpass.kumi.systems"):
            return httpx.Response(200, json={"elements": [
                {"type": "node", "lat": 10.1083, "lon": 76.3529, "tags": {"amenity": "hospital", "name": "Anwar Memorial Hospital",
                                                                          "emergency": "yes", "phone": "+91 484 2624 000"}},
                {"type": "way", "center": {"lat": 10.1300, "lon": 76.3600}, "tags": {"amenity": "clinic", "name": "Far Clinic"}},
                {"type": "node", "lat": 10.1070, "lon": 76.3500, "tags": {"amenity": "hospital", "name": "Janatha Hospital"}},
            ]})
        if host == "www.thehindu.com":
            return httpx.Response(200, text=RSS, headers={"content-type": "application/xml"})
        if host == "news.example.in":
            return httpx.Response(200, text=ARTICLE, headers={"content-type": "text/html; charset=utf-8"})
        if host == "html.duckduckgo.com":
            return httpx.Response(200, text=DDG_HTML, headers={"content-type": "text/html"})
        if host == "en.wikipedia.org" and path == "/w/api.php":
            return httpx.Response(200, json={"query": {"search": [{"title": "Periyar River", "snippet": "longest river"}]}})
        if host == "en.wikipedia.org" and path.startswith("/api/rest_v1/page/summary/"):
            return httpx.Response(200, json={"title": "Periyar River", "description": "river in Kerala, India",
                                             "extract": "The Periyar is the longest river in Kerala.",
                                             "content_urls": {"mobile": {"page": "https://en.m.wikipedia.org/wiki/Periyar_River"}}})
        if host == "evil.example":
            return httpx.Response(302, headers={"location": "http://127.0.0.1:8000/v1/debug/state"})
        return httpx.Response(404)


async def public_resolver(host: str, port: int) -> list[str]:
    return {"localhost": ["127.0.0.1"], "intranet.example": ["10.0.0.5"]}.get(host, ["93.184.216.34"])


@pytest.fixture()
def net():
    return FakeInternet()


@pytest.fixture()
def service(net):
    return InfoService(fetcher=Fetcher(transport=httpx.MockTransport(net), resolver=public_resolver),
                       ai=AI(None), watch_interval_s=3600)


@pytest.fixture()
def client(tmp_path, service):
    app = create_app(tmp_path)
    app.state.hub.services["info"] = service
    with TestClient(app) as c:
        yield c


def hello(ws, label, addr=None):
    ws.send_text(json.dumps({"t": "hello", "node": label.lower(), "label": label, "addr": addr}))


def ask(client, phone, gw, data, trace=("A", "B", "G")):
    """Sends info/query up through a gateway, returns (msg_id, answer data, DOWN envelope)."""
    msg_id, raw = phone.up("info", "query", data, trace=list(trace), hops=len(trace) - 1)
    send_up(gw, raw)
    assert parse_receipt(receive_until(gw, lambda e: e.type == wire.RECEIPT and e.body[:16].hex() == msg_id))[1] == RECEIVED
    down = receive_until(gw, lambda e: e.type == wire.DOWN and phone.open_down(e)["data"].get("ref") == msg_id)
    inner = phone.open_down(down)
    assert inner["svc"] == "info" and inner["kind"] == "answer"
    return msg_id, inner["data"], down


# ---- end to end -----------------------------------------------------------------------------

def test_weather_answer_reaches_the_phone_with_its_cost(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        msg_id, card, down = ask(client, asha, gw, {"tool": "weather", **ALUVA})

    assert card["ok"] and card["tool"] == "weather" and card["title"] == "Weather · Aluva, Kerala"
    assert card["lvl"] == "danger"  # 120 mm in 24 h is IMD "very heavy rain"
    assert card["body"].startswith("⚠ Very heavy rain: 120 mm in 24 h")
    assert "Now 27°C, rain" in card["body"]
    assert [i["t"] for i in card["items"]] == ["Today", "Tomorrow", "Tuesday"]
    assert card["src"] == "Open-Meteo forecast"
    assert card["bytes"] > 0 and not card.get("cached")
    # The size the server announced is exactly what the envelope takes on every hop.
    assert card["wire"] == len(wire.encode(down))
    assert len(down.body) <= wire.MAX_BODY

    [row] = client.get("/v1/debug/state").json()["info"]
    assert (row["id"], row["from"], row["tool"], row["ok"]) == (msg_id, "asha", "weather", True)
    assert row["upPath"] == "A>B>G" and row["wire"] == card["wire"] and row["fetched"] == card["bytes"]
    totals = client.get("/v1/debug/state").json()["infoTotals"]
    assert totals["answered"] == 1 and totals["wire"] == card["wire"]
    page = client.get("/").text
    assert "Live Info" in page and "Weather · Aluva, Kerala" in page


def test_second_ask_is_served_from_cache(client, net):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        ask(client, asha, gw, {"tool": "weather", "place": "Munnar"})
        before = len(net.calls)
        _, card, _ = ask(client, asha, gw, {"tool": "weather", "place": "Munnar"})
    assert len(net.calls) == before and card["cached"] and card["title"] == "Weather · Munnar, Kerala"


def test_alerts_official_first_and_forecast_marked(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        _, card, _ = ask(client, asha, gw, {"tool": "alerts", **ALUVA})
    assert card["title"] == "Alerts · Aluva, Kerala" and card["lvl"] == "danger"
    first, second = card["items"][:2]
    assert first["t"] == "Orange alert · Heavy Rain" and "until" in first["m"] and first["lvl"] == "warn"
    assert second["t"].startswith("Very heavy rain") and "not an official warning" in second["m"]
    assert "Couldn't reach: GDACS" in card["body"]  # one source down doesn't sink the card
    assert "NDMA SACHET" in card["src"]


def test_nearby_sorted_with_coordinates(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        _, card, _ = ask(client, asha, gw, {"tool": "nearby", "cat": "hospital", **ALUVA})
    names = [i["t"] for i in card["items"]]
    assert names == ["Anwar Memorial Hospital", "Janatha Hospital", "Far Clinic"]  # 162 m, 187 m, 2.7 km
    anwar = card["items"][0]
    assert "Open 24/7 · emergency" in anwar["s"] and "☎ +91 484 2624 000" in anwar["m"]
    assert anwar["lat"] == 10.1083 and anwar["lon"] == 76.3529


def test_news_then_read_the_story_in_parts(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        _, news, _ = ask(client, asha, gw, {"tool": "news", "topic": "kerala"})
        assert news["title"] == "News · Kerala"
        top = news["items"][0]
        assert top["t"] == "Shutters of Idukki dam to be raised" and top["s"] == "KSEB will raise two shutters at 2 p.m."
        assert len(top["k"]) == 10  # a 10-character key instead of the URL

        _, part1, _ = ask(client, asha, gw, {"tool": "read", "key": top["k"]})
        assert part1["title"] == "Shutters of Idukki dam to be raised" and part1["part"] == 1 and part1["parts"] >= 2
        assert part1["key"] == top["k"] and part1["src"] == "news.example.in"
        assert part1["body"].startswith("The Kerala State Electricity Board will raise two shutters")
        assert "[1]" not in part1["body"] and "Share on WhatsApp" not in part1["body"] and "Sign in" not in part1["body"]

        _, part2, _ = ask(client, asha, gw, {"tool": "read", "key": top["k"], "part": 2})
        assert part2["part"] == 2 and part2["body"] != part1["body"] and part2["cached"]


def test_ask_without_ai_gives_web_results(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        _, card, _ = ask(client, asha, gw, {"tool": "ask", "q": "is kochi airport open"})
    assert card["tool"] == "ask" and not card.get("ai") and "AI answers are off" in card["body"]
    assert [i["t"] for i in card["items"]] == ["CIAL flight status"]  # the ad is gone
    assert card["items"][0]["m"] == "cial.aero" and card["src"] == "DuckDuckGo"


def test_bad_requests_get_a_readable_error(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        _, card, _ = ask(client, asha, gw, {"tool": "weather"})
        assert not card["ok"] and card["body"] == "Turn on location, or type a place name"
        _, card, _ = ask(client, asha, gw, {"tool": "weather", "place": "Nowhereville"})
        assert not card["ok"] and card["title"] == "Couldn't get the weather" and "Nowhereville" in card["body"]
        _, card, _ = ask(client, asha, gw, {"tool": "read", "url": "http://localhost:8000/v1/debug/state"})
        assert not card["ok"] and "public internet" in card["body"]
        _, card, _ = ask(client, asha, gw, {"tool": "teleport"})
        assert not card["ok"]


def test_watch_pushes_only_new_alerts(client, service, net):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G", addr=asha.address)  # her own phone is the gateway: pushes reach her directly
        msg_id, raw = asha.up("info", "watch", {"topic": "alerts", **ALUVA, "hours": 24})
        send_up(gw, raw)
        first = asha.open_down(receive_until(gw, lambda e: e.type == wire.DOWN))
        assert first["data"]["ref"] == msg_id and first["data"]["watch"] > int(time.time() * 1000)
        assert first["data"]["title"] == "Alerts · Aluva, Kerala"

        assert client.portal.call(service.check_watches) == 0  # nothing new yet

        net.sachet.append(sachet_alert(2, 10.12, 76.36, color="red"))
        service.fetcher.clear_cache()  # in production the 2-minute SACHET cache simply expires
        assert client.portal.call(service.check_watches) == 1
        push = asha.open_down(receive_until(gw, lambda e: e.type == wire.DOWN))["data"]
        assert push["title"] == "New alert · Aluva, Kerala" and "ref" not in push and push["watch"]
        assert [i["t"] for i in push["items"]] == ["Red alert · Heavy Rain"] and push["lvl"] == "danger"
        assert client.portal.call(service.check_watches) == 0  # told once

        _, raw = asha.up("info", "unwatch", {"topic": "alerts"})
        send_up(gw, raw)
        off = asha.open_down(receive_until(gw, lambda e: e.type == wire.DOWN and asha.open_down(e)["kind"] == "watch"))
        assert off["data"] == {**off["data"], "topic": "alerts", "on": False}
        net.sachet.append(sachet_alert(3, 10.12, 76.36))
        service.fetcher.clear_cache()
        assert client.portal.call(service.check_watches) == 0


# ---- AI ---------------------------------------------------------------------------------------

def test_ai_answer_with_cited_sources(tmp_path):
    def claude(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.anthropic.com":
            body = json.loads(request.content)
            assert body["model"] == "claude-haiku-5-5" and body["tools"][0]["type"] == "web_search_20250305"
            assert request.headers["x-api-key"] == "test-key"
            return httpx.Response(200, json={"stop_reason": "end_turn", "content": [
                {"type": "server_tool_use", "id": "s1", "name": "web_search", "input": {"query": "kochi airport"}},
                {"type": "web_search_tool_result", "tool_use_id": "s1", "content": [
                    {"type": "web_search_result", "url": "https://cial.aero/news", "title": "CIAL news"}]},
                {"type": "text", "text": "Yes. Kochi airport is operating normally today.",
                 "citations": [{"type": "web_search_result_location", "url": "https://cial.aero/news",
                                "title": "CIAL news", "cited_text": "Operations normal"}]},
            ]})
        return httpx.Response(404)

    service = InfoService(fetcher=Fetcher(transport=httpx.MockTransport(claude)), ai=AI("test-key"), watch_interval_s=3600)
    app = create_app(tmp_path)
    app.state.hub.services["info"] = service
    with TestClient(app) as c:
        asha = Phone("asha", "Asha")
        asha.register(c)
        with c.websocket_connect("/v1/gateway/ws") as gw:
            hello(gw, "G")
            _, card, _ = ask(c, asha, gw, {"tool": "ask", "q": "Is Kochi airport open?"})
    assert card["ai"] and card["body"] == "Yes. Kochi airport is operating normally today."
    assert card["items"][0]["t"] == "CIAL news" and card["items"][0]["m"] == "cial.aero"


# ---- units ------------------------------------------------------------------------------------

def test_fit_keeps_every_card_inside_one_envelope():
    malayalam = "മഴ ശക്തമാകും " * 400  # 3 bytes a character
    card = Card(tool="news", title="News", body=malayalam,
                items=[Item(t=f"Headline {i}", s=malayalam[:600], m="The Hindu") for i in range(12)])
    data = fit(card.to_data("r" * 32, 0, 123456, False))
    assert size_of(data) <= MAX_DATA_BYTES and len(data["items"]) >= 2 and data["body"]


def test_reader_text_and_parts():
    page = html_to_text(ARTICLE)
    assert page.title == "Shutters of Idukki dam to be raised"
    assert page.paragraphs[0].startswith("The Kerala State Electricity Board")
    assert not any("Share on" in p or "Copyright" in p or "Sign in" in p for p in page.paragraphs)
    parts = split_parts(page.paragraphs)
    assert len(parts) >= 3 and all(len(p.encode("utf-8")) <= 2600 for p in parts)
    assert "\n\n".join(parts).count("Paragraph 59") == 1


def test_feed_parsing():
    items = parse_feed(RSS.encode())
    assert [i.title for i in items] == ["Shutters of Idukki dam to be raised", "Relief camps opened in Aluva"]
    assert items[0].summary == "KSEB will raise two shutters at 2 p.m." and items[0].published


@pytest.mark.parametrize("url", ["http://localhost/", "http://127.0.0.1:8000/", "http://10.0.0.5/", "http://intranet.example/",
                                 "http://[::1]/", "file:///etc/passwd", "http://user:pw@example.com/", "http://169.254.169.254/"])
def test_reader_refuses_private_addresses(url):
    import asyncio
    fetcher = Fetcher(transport=httpx.MockTransport(FakeInternet()), resolver=public_resolver)
    with pytest.raises(FetchError):
        asyncio.run(fetcher.get(url, guard=True))


def test_reader_checks_every_redirect():
    import asyncio
    fetcher = Fetcher(transport=httpx.MockTransport(FakeInternet()), resolver=public_resolver)
    with pytest.raises(FetchError, match="public internet"):
        asyncio.run(fetcher.get("https://evil.example/go", guard=True))
