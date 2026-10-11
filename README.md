# Hopnet

Use online services with no internet. A request is sealed into a ~0.5 KB envelope and hops phone to phone over Bluetooth (Google Nearby Connections) until it reaches any phone that is online. That phone uploads it to the Hopnet server, which provides the service and sends the reply back the same way.

This repo ships three services on top of a hopping layer that is service-agnostic:
- **Messages**: text anyone on Hopnet, through the server.
- **Live Info**: the internet's essentials with no signal. A phone asks a ~200-byte question; the server reads the internet (weather, official alerts, maps, news, any web page or PDF) and sends back one signed card of at most ~3.4 KB. A briefing that costs the server ~700 KB of downloads reaches the phone as ~1.5 KB. See below.
- **Find Me**: someone lost in a crowd (e.g. Sabarimala) shares their name and GPS location with every Hopnet phone nearby. It needs **no server and no internet**: it hops phone to phone, and helpers see "120 m north-east" with an arrow. If any phone on the way has internet, it also reaches the server, and the police see it on `/police`.

Food ordering and tickets appear in the app as "coming soon" and plug in the same way.

| Path | What it is |
|---|---|
| `core/` | Pure Kotlin hopping engine: envelope format, routing (flood, hop limit, duplicate filter, store-and-forward, receipts), gateway WebSocket client, protocol client. JVM-testable. |
| `sdk/` | Android library: Nearby Connections transport, Room store, foreground relay service, Keystore-protected keys, `Hopnet` facade. |
| `app/` | The Hopnet app: onboarding, home with service catalog, Messages, Network radar and demo controls. |
| `server/` | FastAPI + SQLite server: registration, gateway WebSocket, mailbox, receipts, service handlers, live debug page, Find Me police page. |
| `server/hopnet_server/info/` | Live Info: the tools that read the internet and squeeze the answer into one envelope. |
| `docs/messaging-mvp-plan.md` | **The plan: how the communication works**, with the protocol spec and demo runbook. |
| `docs/android-implementation-plan.md` | Longer-term vision (food, SMS fallback...). |

## Run the server

```bash
cd server
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt      # Windows (use .venv/bin/... on macOS/Linux)
.venv/Scripts/python -m uvicorn hopnet_server.main:app --host 0.0.0.0 --port 8000
```

Open `http://<laptop-ip>:8000/` for the live view of gateways, users, hop paths and the mailbox, and `http://<laptop-ip>:8000/police` for the Find Me police page (list and map of lost people). Keys and the database are created in `server/data/` on first start.

## Build and install the app

Requires Android Studio (AGP 8.13, compileSdk 36). Open the project and run `app`, or:

```bash
./gradlew :app:assembleDebug -Phopnet.serverUrl=http://<laptop-ip>:8000
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, while online, enter a name, a username, a phone label (A, B, G...) and the server URL. Then grant the Nearby, Location and Notification permissions. After that the phone relays in the background, and works offline.

## Tests

```bash
./gradlew :core:test                                   # protocol + multi-phone routing simulation
cd server && .venv/Scripts/python -m pytest -q         # server, incl. two-gateway end-to-end flow
# Everything except the radios: Kotlin mesh + real crypto + real WebSocket + real server
HOPNET_LIVE_SERVER=http://127.0.0.1:8000 ./gradlew :core:test --tests '*LiveServerTest*'
```

`server/tools/fake_phone.py` plays a second user from the laptop, which is useful when you have only one device:

```bash
cd server && .venv/Scripts/python -m tools.fake_phone --server http://127.0.0.1:8000 --handle ravi --send-to asha --text "hi" --listen 60
```

## Live Info

Open **Live Info** on the home screen. Everything goes up as `info/query` and comes back as one `info/answer` card that shows its source, when it was fetched, what the server downloaded vs what the mesh carried, and the hop path both ways.

| Ask | Server reads | Notes |
|---|---|---|
| **Briefing** | weather + official alerts + headlines, in one reply | the cheapest way to get informed over a mesh |
| **Weather** | Open-Meteo | warns with IMD rainfall categories (heavy ≥ 64.5 mm/day), wind, thunder, heat |
| **Official alerts** | NDMA SACHET (IMD, CWC, state disaster authorities), USGS, GDACS | filtered to your location; forecast notes are labelled as such |
| **Hospitals, shelters, pharmacies, police, fire, water, food, fuel, ATMs** | OpenStreetMap (Overpass, Nominatim fallback) | each place carries coordinates, so the phone shows live distance as you walk; tap to dial |
| **News** | The Hindu / BBC RSS by topic, Google News for search | tap a headline to read the story |
| **Ask / Web / Wikipedia** | DuckDuckGo, Wikipedia; Claude + web search if `ANTHROPIC_API_KEY` is set | AI answers are labelled and list their sources |
| **Open a link** | any page or PDF (e.g. a government circular) | read in ~2.6 KB parts; private and local addresses are refused |
| **Alert watch** | re-checks official alerts for your spot every 5 min for 3 days | new warnings are pushed through the mesh with a notification |

Places can be your GPS fix (GPS needs no internet) or a typed name ("Munnar", "Wayanad", "Sabarimala"). Answers are cached on the server (weather 15 min, alerts 2 min, maps 6 h...), so a crowd asking the same thing costs one fetch. Settings: `ANTHROPIC_API_KEY` (optional AI answers), `HOPNET_AI_MODEL` (default `claude-haiku-5-5`), `HOPNET_INFO_WATCH_S` (watch interval, default 300).

Check every source before a demo (public map servers come and go), and play a phone from the laptop:

```bash
cd server && .venv/Scripts/python -m tools.live_info                 # every tool against the real internet
.venv/Scripts/python -m tools.fake_phone --handle ravi --info '{"tool":"brief","lat":10.1076,"lon":76.3516}' --listen 90
```

**Demo:** with phones chained A↔B↔C↔G and only G online, tap **Briefing** on A. The card shows "Waiting for nearby phones", then "Passed to 1 phone", then "Reached the server · reading the internet", and the answer arrives with `⬆ A›B›C›G ⬇ cloud›G›C›B›A` and "714 KB from the internet → 1.6 KB through the mesh · 433× smaller". The server page's Live Info table and header show the same totals.

## Demo: Find Me with no internet

Install this build on **every** phone and restart the server: older builds and servers don't know the new BROADCAST envelope and drop it.

1. Register every phone while online, then put them all in airplane mode with Bluetooth and Location on. Chain them A↔B↔C↔D (see below). No phone has internet.
2. On A: **Find Me → Find Me**, add a note, send. Within seconds C and D get a notification and show "Ravi needs help · 120 m north-east" with an arrow. No server was involved.
3. Turn on D's Wi-Fi. D uploads the alert, `/police` shows it on the map with the path A › B › C › D, and A shows "✓ The police have your alert".
4. On A: **I'm found / safe**. The alert turns green on every phone and on `/police`.

## Demo (multi-hop)

See §11 of `docs/messaging-mvp-plan.md`:
1. Register every phone while online.
2. Switch to airplane mode, then turn Bluetooth and Location back on.
3. Use **Network → Demo: force a chain** so the phones form A↔B↔C↔G.
4. Keep one phone (G) online as the gateway.
