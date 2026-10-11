# Hopnet: Messaging plan (first version)

> This is the plan for **what we are building now**. `android-implementation-plan.md` is the long-term vision (food, SMS fallback and so on).
> Current scope: one working service, **Messages**, on top of a reusable hopping layer that later services (food ordering, tickets, SOS) plug into without changes.

## Status (2026-10-10)

| Piece | State | How it was verified |
|---|---|---|
| Wire protocol, routing (flood, hop limit, duplicate filter, store-and-forward, gateway, receipts) | ✅ built | 14 JVM tests, including line, diamond, hop-limit, late-path, gateway handover, receipt purge and forged-receipt scenarios |
| Server (register, gateway WebSocket, mailbox, receipts, sys + chat services, debug page) | ✅ built | 7 pytest tests (two-gateway flow, idempotency, unknown user, forged signature, unknown service) |
| Kotlin ⇄ Python interop | ✅ | Shared byte vector in both test suites. `LiveServerTest` runs the Kotlin mesh (A–B–G and R–G2) against the real server: the path A>B>G reaches the server, R receives, A sees "delivered", the relays are purged. |
| Android SDK and app | ✅ builds and runs | On an API 36 emulator: libsodium/JNA load, Keystore identity, registration, gateway mode, receiving (with notification), sending, ✓✓ delivered, and an offline message queued then uploaded on reconnect |
| Nearby multi-hop between real phones | ⚠️ not yet verified | An emulator has no real Bluetooth peers. Discovery and advertising start correctly. **Test on 2, then 4–5 phones next.** |

**Learned while building:**
- **Nearby discovery needs the location permission even on Android 16.** The emulator reported `MISSING_PERMISSION_ACCESS_COARSE_LOCATION`. The app now asks for location on every version and warns if the Location switch is off.
- **Dependency ceiling:** the project stays on AGP 8.13 / compileSdk 36, which caps Compose BOM at `2026.06.01` and OkHttp at `5.4.0`. Newer releases need compileSdk 37 and AGP 9.1.
- lazysodium-android `5.2.0` ships 16 KB-aligned native libraries, required on newer devices. JVM tests use lazysodium-java `5.1.4`, which has the same API on Java 17.

---

## 1. Scope

| In scope now | Out of scope (later) |
|---|---|
| Android app (Kotlin, Compose) with a **service catalog** (Messages available; Food, Tickets, SOS shown as "coming soon") | Food ordering, tickets, SOS |
| **Hopping layer**: Nearby Connections mesh, store-and-forward, hop limit, duplicate filtering, receipts | SMS fallback (dropped for now) |
| **Gateway mode**: any phone with internet uploads mesh traffic to the server and injects replies | Restaurant dashboard, MCP and ONDC adapters |
| **Server** (FastAPI, SQLite): registration, gateway WebSocket, mailbox, Messages service | Production hardening (TLS pinning, rate limiting, horizontal scale) |
| Encryption and signing (libsodium), so relays cannot read or forge anything | End-to-end encryption between users (server can read messages for now; see §9) |
| Mesh "radar" screen and hop-path display for the demo | Gateway gradient routing, catalog gossip |

---

## 2. Design decisions

1. **The server is the post office, and messages always go through it.**
   We could deliver phone-to-phone directly inside one mesh, but going through the server keeps a single model for every service. A message, a food order and an SOS are all "request to server; server sends something to someone". It also works when sender and recipient are in **different** meshes, cities or networks. The cost is that a message only arrives once *some* gateway exists. That matches the pitch.

2. **Addresses come from a one-time online registration.**
   Each phone creates its keys locally, then registers once while online with a **handle** (`@ravi`) and display name. After that it works fully offline. The sender only needs the recipient's handle, not their key, because the server re-encrypts for the recipient. Offline you can message anyone whose handle you know.

3. **Finding an offline recipient: the "PO box" poll.**
   The server can't push to a phone it can't reach. Every offline phone with at least one neighbour periodically sends a tiny **POLL** envelope toward the server. Whichever gateway carries it gets subscribed to that phone's address for 10 minutes. The server then pushes that phone's pending mail to that gateway, and the gateway floods it back into its mesh. The gateway never learns *who* polled, because polls are sealed like everything else. The server works out the mapping.

4. **Every delivery happens at least once, and duplicates are filtered out.**
   Mesh flooding deliberately creates duplicates: several paths and several gateways. Every layer de-duplicates by `msgId`:
   - relays keep a "seen" table;
   - the server ignores an upload it has already processed and replays the receipt;
   - the app's database uses unique message ids.

5. **Signed receipts stop the flood.**
   When the server accepts an envelope, it issues a **RECEIPT** signed with its key. The receipt floods back, every relay deletes its stored copy, and the sender's tick turns into "reached server". Receipts must be signed, otherwise anyone could kill messages with fake receipts.

6. **An online phone is just a gateway.**
   There is no separate "online mode" code path. A phone with internet connects to the server's WebSocket and becomes a gateway. Its own messages go straight up, and its own mail comes straight down. The same Router handles both cases.

7. **Nearby Connections with `P2P_CLUSTER`.**
   This is the only Nearby strategy that allows many-to-many links. It runs over Bluetooth and Bluetooth LE, which is plenty for ~1 KB envelopes. Each phone is limited to 4 simultaneous links.

8. **The hopping engine is pure Kotlin (`:core`), with no Android.**
   Router, envelope codec, crypto plumbing, gateway client and protocol client can then be **tested on the JVM**, including a multi-node mesh simulation against the real Python server, without phones.

**Constraints to be aware of:**
- First launch needs internet once, for registration.
- Delivery takes seconds to minutes, not milliseconds.
- Nearby connection setup takes a few seconds per hop.
- Aggressive OEM battery managers may kill background relaying. A foreground service mitigates this.

---

## 3. Architecture

```
Hopnet/
├── core/   (Kotlin/JVM: no Android)   app.hopnet.core
│   ├── protocol/  Envelope, EnvelopeCodec, Frame, SealedPayload, Receipts, Hex, Ids
│   ├── crypto/    HopCrypto interface, Identity, ServerKeys
│   ├── store/     EnvelopeStore interface + InMemoryEnvelopeStore
│   ├── mesh/      Router (routing rules), MeshNode (actor), MeshTransport interface
│   ├── gateway/   GatewayClient (OkHttp WebSocket), Uplink interface
│   └── client/    HopnetClient (service messages ⇄ envelopes), ServerApi (HTTP)
├── sdk/    (Android library)            app.hopnet.sdk
│   ├── Hopnet.kt            public facade (init, register, send, incoming, status)
│   ├── nearby/NearbyTransport.kt    Google Nearby Connections → MeshTransport
│   ├── store/               Room: envelopes, seen, receipts → EnvelopeStore
│   ├── crypto/SodiumCrypto.kt       lazysodium-android → HopCrypto
│   ├── identity/            Keystore-wrapped keys + profile
│   ├── net/ConnectivityMonitor.kt
│   └── service/MeshService.kt       foreground service (connectedDevice)
├── app/    (Hopnet app)                 app.hopnet
│   ├── services/ServiceCatalog.kt   list of services shown on Home
│   ├── services/messaging/          chat DB, repository, screens
│   └── ui/                          onboarding, home, network radar, theme
└── server/ (Python FastAPI + SQLite)    hopnet_server
    ├── envelope.py, crypto.py, keys.py, db.py
    ├── hub.py               gateway sessions, subscriptions, mailbox push, receipts
    └── services/            base.py, system.py (poll/ack), chat.py  ← food.py later
```

```
 ┌──────────── app ────────────┐
 │ Home (service catalog)      │
 │ Messages UI ─ ChatRepository│──┐ Hopnet.send("chat","message",{to,text})
 └─────────────────────────────┘  │ Hopnet.incoming / Hopnet.updates
 ┌──────────── sdk ─────────────────▼─────────────────────────────┐
 │ Hopnet facade ── HopnetClient (core): seal/sign, open/verify,  │
 │                  polls, acks                                   │
 │ MeshNode (core actor) ── Router (core)                         │
 │    ├─ NearbyTransport (P2P_CLUSTER)  ← phone-to-phone          │
 │    ├─ GatewayClient (WebSocket)      ← when phone is online    │
 │    └─ RoomEnvelopeStore              ← store-and-forward       │
 │ MeshService (foreground) keeps it alive in the background      │
 └────────────────────────────────────────────────────────────────┘
```

---

## 4. How communication works

### 4.1 Identities and addresses

- On first launch the phone generates an **Ed25519 signing keypair** and an **X25519 encryption keypair** (libsodium).
- Private keys are encrypted with an AES-GCM key held in the **Android Keystore**.
- `address = hex(SHA-256(signPk)[0..16])`, a 16-byte mailbox id.
- Registration (online, once): `POST /v1/register {handle, name, signPk, encPk, sig}`, where `sig` proves possession of the signing key. The response contains the address and the **server's public keys** (signing and encryption), which the phone stores.
- The server keeps `handle → (signPk, encPk, address)`.

### 4.2 Envelope: the only thing relays ever see

Binary, big-endian. Every hop carries exactly this:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | magic `0x48` ('H') | |
| 1 | 1 | version `1` | |
| 2 | 1 | type | `1` UP (to server), `2` DOWN (to an address), `3` RECEIPT, `4` BROADCAST (to every phone, §5.1) |
| 3 | 1 | flags | bit 0 = TRACE (collect hop path) |
| 4 | 16 | msgId | random; key for duplicate filtering and idempotency |
| 20 | 16 | dest | UP/RECEIPT: zeros. DOWN: recipient address |
| 36 | 1 | hops | link traversals so far (each receiver adds 1) |
| 37 | 1 | maxHops | default 12; not forwarded once `hops ≥ maxHops` |
| 38 | 8 | createdAt | epoch milliseconds |
| 46 | 8 | expiresAt | chat 24 h, poll 90 s, ack 6 h, receipt 24 h |
| 54 | 2 | bodyLen | ≤ 4096 |
| 56 | n | body | sealed (UP/DOWN) or signed receipt |
| 56+n | 1 | traceCount | ≤ 16 |
| … | 1+k each | trace labels | UTF-8, ≤ 24 bytes each (demo: "A", "B", "G") |

Only `hops` and `trace` change in transit. Neither is trusted for security.

### 4.3 Sealed payload (inside UP and DOWN)

```
plaintext = signature(64) | jsonLen(u16) | json | zero padding (round up to a multiple of 256)
body      = crypto_box_seal(plaintext, recipientEncPk)      // +48 bytes
```

- **UP:** sealed to the **server**, signed by the **sender**:
  `{"v":1,"id":<msgId>,"from":<sender address>,"ts":…,"exp":…,"svc":"chat","kind":"message","data":{…}}`
- **DOWN:** sealed to the **recipient**, signed by the **server**:
  `{"v":1,"id":<msgId>,"to":<recipient address>,"ts":…,"exp":…,"svc":"chat","kind":"message","data":{…}}`
- `id` and `exp` must equal the header values, so a relay that tampers with the header is detected.
- The padding hides message length from relays.
- A typical chat message comes to about 560 bytes on the wire.

### 4.4 Receipt body

```
target msgId(16) | status(1) | hops(1) | traceLen(1) | trace ("A>B>G", UTF-8) | serverSig(64)
serverSig = Ed25519("HNR1" | everything before the signature)
status: 1 RECEIVED (UP reached the server) · 2 DELIVERED (DOWN acked by recipient) · 3 REJECTED
receipt msgId = SHA-256("HNR1" | target | status)[0..16]   → the same receipt de-duplicates everywhere
```

### 4.5 Link layer: Google Nearby Connections

- Service id is `app.hopnet.mesh.v1`. Every app embedding the SDK must use the same id to join one mesh.
- Strategy is `P2P_CLUSTER`, and every phone **advertises and discovers at the same time**.
- Endpoint name is `HN1|<nodeId>|<label>|<gateway 0/1>`. A phone can see who is a gateway before connecting, and re-advertises when its gateway status changes.
- **Connection policy:**
  - At most 4 links; gateways are tried first.
  - Connections are auto-accepted, because security comes from the envelope crypto.
  - **Collision rule:** only the side with the smaller `nodeId` requests the connection. The other side waits 6 s before trying itself.
  - Failed endpoints back off exponentially.
  - A maintenance loop every 5 s retries discovered but unconnected endpoints.
- **Demo allowlist:** an optional set of labels. A phone only connects to, or accepts, listed labels. This forces a chain A↔B↔C↔G in a room where every phone can see every other phone.
- **Frames** (Nearby `BYTES` payloads):
  - `0x01 HELLO {"node","label","gw"}`, sent on connect and whenever gateway status changes.
  - `0x02 ENV <envelope bytes>`.

### 4.6 Routing rules (Router, in `:core`)

```
on envelope from link L:
  validate (magic, size, maxHops ≤ 32, not expired, expiry ≤ 7 days)
  if seen(msgId): drop                     ← duplicate filtering
  markSeen; hops += 1; append my label to trace
  RECEIPT: verify server signature → remember target as done, delete stored copy,
           tell the app (if it is mine), store + flood
  UP:      if target already receipted: drop
           store; if I am a gateway: upload (don't flood) else flood to all links except L
  DOWN:    if receipted: drop
           if dest == my address: deliver to app (don't forward)
           else store + flood
flood: only if hops < maxHops; send to every link except the one it came from
on link up: send the new peer every stored, unexpired envelope (receipts first)  ← store-and-forward
on becoming a gateway: upload every stored UP envelope
housekeeping (60 s): purge expired envelopes, old seen ids, store cap 1000 (oldest relayed first, own messages never evicted)
```

### 4.7 Gateway protocol (phone ⇄ server WebSocket `/v1/gateway/ws`)

| Direction | Message | Meaning |
|---|---|---|
| phone → server | `{"t":"hello","node":…,"label":…,"addr":<own address or null>}` | Session start. The server subscribes the gateway's own address. |
| phone → server | `{"t":"up","envs":[base64…]}` | Upload envelopes from the mesh, or the phone's own. |
| server → phone | `{"t":"down","envs":[base64…]}` | DOWN envelopes and RECEIPTs to deliver or flood into the mesh. |

The phone is a gateway **while this socket is open**. It tries to connect whenever Android reports a network. Reconnects back off from 1 s up to 15 s, and pings every 15 s detect dead links.

### 4.8 Server processing

```
upload(env) from session S:
  already processed msgId?  → resend its receipt to S (idempotent), refresh subscription
  open sealed box with server key → verify sender signature, id/exp match, sender registered
  invalid → REJECTED receipt
  record upload (sender, svc, kind, hops, trace) ; subscribe S to sender address (10 min)
  send RECEIVED receipt to S
  dispatch to service handler (services[svc]).handle(...)  → may call ctx.send_down(...)
  push sender's pending mailbox to S

send_down(address, svc, kind, data):
  build DOWN (signed by server, sealed to recipient encPk, trace ["cloud"]) → mailbox
  push to every live session subscribed to that address (each envelope once per session)

sys/ack {ids} from recipient:
  mark mailbox items delivered → DELIVERED receipt to every session it was pushed to
  services[item.svc].on_delivered(item)   (chat: tell the original sender "delivered")
```

### 4.9 End-to-end: Asha (offline) messages Ravi (offline, in another mesh)

```
Asha(A)        Relay(B)        Gateway(G)           Server                    Gateway(G2)     Ravi(R)
  │ UP ─────────▶│                │                    │                          │              │
  │              │ UP hops=1 ────▶│ up ───────────────▶│ verify+decrypt           │              │
  │              │                │                    │ chat → mailbox(Ravi)     │              │
  │              │                │◀── RECEIPT(RECEIVED, A>B>G)                   │              │
  │◀── RECEIPT ──│◀── RECEIPT ────│  (relays delete their copies)                 │              │
  │ ✓ "reached server · 2 hops"   │                    │                          │◀── POLL ─────│ (every 30 s)
  │              │                │                    │◀─────────── up(POLL) ────│              │
  │              │                │                    │ subscribe Ravi@G2        │              │
  │              │                │                    │── down(DOWN msg) ───────▶│── DOWN ─────▶│ decrypt+verify
  │              │                │                    │◀─────────── up(ACK) ─────│◀── UP(ack) ──│
  │              │                │                    │── RECEIPT(DELIVERED) ───▶│── purge ────▶│
  │              │                │◀── down(DOWN status "delivered", Asha subscribed via G)
  │◀────────── DOWN status ───────│                    │                          │              │
  │ ✓✓ "delivered"                │                    │                          │              │
```

### 4.10 Message states shown to the user

| State | Trigger | UI |
|---|---|---|
| `QUEUED` | Stored on my phone, no neighbours yet | 🕓 Waiting for nearby phones |
| `RELAYED` | Handed to at least 1 neighbour (or uploaded directly) | ↗ Passed to N phones |
| `AT_SERVER` | RECEIPT(RECEIVED) arrived | ✓ Reached Hopnet · N hops · path A › B › G |
| `DELIVERED` | chat/status `delivered` arrived | ✓✓ Delivered |
| `FAILED` | REJECTED receipt or status `failed` (for example, unknown handle) | ⚠ reason |

---

## 5. Messages service

| Envelope | svc/kind | data |
|---|---|---|
| UP | `chat/message` | `{"to":"ravi","text":"…"}` |
| DOWN | `chat/message` | `{"from":"asha","fromName":"Asha","text":"…","sentAt":…,"ref":<sender msgId>}` |
| DOWN | `chat/status` | `{"ref":<sender msgId>,"status":"delivered"|"failed","reason":…}` |
| UP | `sys/poll` | `{}`: "I'm here, send my mail" |
| UP | `sys/ack` | `{"ids":[DOWN msgIds]}`: sent automatically by the SDK for every DOWN received |

App database (Room, `chat.db`): `conversations(peerHandle, peerName, lastText, lastAt, unread)` and `messages(id, peerHandle, outgoing, text, sentAt, status, relayPeers, hops, path, failReason)`.

---

### 5.1 Find Me (BROADCAST, no server needed)

Messages go through the server. Find Me must work when **no phone has internet**, so it uses a fourth envelope type:

- **BROADCAST** body: `signPk(32) | signature(64) | jsonLen(u16) | json`, where `signature = Ed25519("HNB1" | json)`. Signed, **not encrypted**: every phone can read it, so people nearby can help. JSON: `{"v","id","from","ts","exp","svc","kind","data"}`, and `id`/`from`/`ts`/`exp` must match the header.
- `dest` is the **sender's address**. Every phone checks `dest == SHA-256(signPk)[0..16]` and the signature before relaying, so nobody can fake, change or replace someone else's alert.
- **Newest wins:** each phone keeps only the newest broadcast per sender (by `createdAt`). Location updates and "found" replace the old alert everywhere, and late old copies are ignored.
- Every phone shows it and floods it, **gateways included**. A gateway also uploads it. The server's RECEIPT marks it "police have it" but **does not purge** it, because people nearby still need it.
- Broadcasts sync first on every new link, are never evicted by the store cap, use maxHops 16 and expire after 12 h.

| kind | data |
|---|---|
| `findme/lost` | `{"case","name","handle","lat","lon","acc","fixAt","note","battery"}` |
| `findme/found` | same; closes the case |

The phone re-sends while lost: at once, on the first GPS fix, after moving ≥ 25 m (or more than the GPS accuracy), and every 3 minutes. The relay foreground service also has type `location`, so this works with the screen off. Helpers see distance and direction from their own GPS (no map tiles needed) and a compass arrow. Server: `services/findme.py` stores one row per case, `GET /v1/findme/cases` serves them, and `/police` shows a list and a map.

## 6. Adding the next service (for example, Food ordering)

Nothing below the service layer changes. Envelopes, routing, gateways, receipts and acks are all generic.

1. **App:** add an entry in `ServiceCatalog` (`id="food"`, `available=true`, `route="food"`), then build screens and a repository that call
   `Hopnet.send(service = "food", kind = "order", data = {...})` and collect `Hopnet.incoming.filter { it.service == "food" }`.
2. **Server:** create `services/food.py` with `class FoodService(ServiceHandler)`, where `handle()` places the order (mock restaurant, Swiggy MCP or ONDC adapter) and calls `ctx.send_down(sender, "food", "status", {...})` for "accepted" and "out for delivery". Register it in `services/__init__.py`.
3. Delivery states, receipts, acks, polling and gateways work as they do for Messages.

---

## 7. Android components

| Component | Responsibility |
|---|---|
| `Hopnet` (sdk facade) | `init`, `register`, `startMesh`/`stopMesh`, `send(service, kind, data)`, `incoming`, `updates`, `status`, `log`, demo settings |
| `MeshService` | Foreground service of type `connectedDevice`. Starts and stops Nearby and keeps the process alive, with notification "Hopnet active · N nearby". |
| `NearbyTransport` | Advertising, discovery, connection policy, payload I/O. Emits `LinkUp`, `LinkDown` and `Received`. |
| `MeshNode` (core) | Single-coroutine actor. Owns the Router, frames, peers, stats and log, and connects the transport, uplink and store. |
| `GatewayClient` (core) | WebSocket session, reconnect and back-off, hello, upload, down. |
| `HopnetClient` (core) | Builds and opens sealed payloads, sends polls every 30 s while offline with neighbours, batches acks, and maps receipts to status updates. |
| `RoomEnvelopeStore` | Tables `envelopes`, `seen` and `receipts`, with an in-memory LRU for seen ids. |
| `ConnectivityMonitor` | `ConnectivityManager` callback. The gateway client tries to connect while any network is up. |

**App screens:**
- **Onboarding:** server URL, name, handle and device label, then Register (online), then Permissions.
- **Home:** status card (online gateway / N nearby / alone), services grid (Messages, plus Food, Tickets and SOS marked "Soon"), and an entry to Network.
- **Messages:** conversation list and "new chat by handle".
- **Chat:** bubbles with state ticks and hop path.
- **Network** (radar): my label, node and gateway; peers; stats; live log; demo allowlist; reset store; battery-optimisation shortcut.

**Permissions:**
- All versions: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`. Nearby discovery fails without them even on Android 16, and the Location switch must be on.
- API ≥ 31: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
- API ≥ 33: `NEARBY_WIFI_DEVICES` and `POST_NOTIFICATIONS`.
- Plus install-time permissions for Wi-Fi state, network, foreground service and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

---

## 8. Server components

| Endpoint | Purpose |
|---|---|
| `GET /v1/info` | Server public keys |
| `POST /v1/register` | Register or refresh a handle with keys (proof of possession signature) |
| `GET /v1/users/{handle}` | Look up a handle (used by "new chat" when online) |
| `WS /v1/gateway/ws` | Gateway protocol (§4.7) |
| `GET /` | Live debug page: users, recent uploads with **hop paths**, mailbox |

SQLite tables are `users`, `uploads` (idempotency and receipts), `mailbox`, and an `events` log for the debug page. Server keys are generated on first start into `server/data/server_keys.json`, which is git-ignored.

---

## 9. Security notes and known limitations

- Relays and gateways see only envelopes: type, msgId, destination address, hop count and the demo trace. They cannot read, forge or alter content.
- The server *can* read messages. That's acceptable for a service platform, but for private chat the next step is **end-to-end encryption to the recipient**: cache recipient public keys when online and add an inner `crypto_box`.
- Downlink envelopes show the recipient address to relays. Rotating per-session mailbox tokens would hide that.
- The server keys come from the registration response (trust on first use over HTTP in the hackathon). In production, pin them in the build and use HTTPS.
- Floods are bounded by hop limit, expiry, store cap and receipts. Per-link rate limiting is still a TODO.

---

## 10. Testing

1. **`:core` unit tests (JVM, no phones):**
   - codec round-trips;
   - sealed payload and receipt signing with real libsodium (lazysodium-java);
   - Router scenarios with in-memory transports: line, diamond with duplicates, hop limit, store-and-forward when a link appears later, gateway upload, receipt purge.
2. **Server tests (pytest):** full flow with two gateway WebSockets: upload, receipt, poll, push, ack, delivered status.
3. **Live integration test (JVM against the real server):** mesh 1 is A–B–G and mesh 2 is R–G2, using real crypto, WebSockets and server. It asserts "reached server via A>B>G", that Ravi receives the text, and that Asha sees "delivered". Run it with `HOPNET_LIVE_SERVER=http://127.0.0.1:8000`.
4. **On devices:**
   - two phones, then a chain of 4 or 5 with the allowlist;
   - toggle gateway Wi-Fi or data;
   - screen off for 10 minutes;
   - force-stop and relaunch.

---

## 11. Demo runbook

1. Run the server on a laptop: `cd server && uvicorn hopnet_server.main:app --host 0.0.0.0 --port 8000`. Open `http://<laptop-ip>:8000/` on the projector.
   - If the gateway phone uses mobile data instead of the laptop's Wi-Fi, expose the server with a tunnel (cloudflared or ngrok) and use that URL.
2. On every phone, while online: register (handles `asha`, `ravi`, …; labels `A`, `B`, `C`, `G`) and grant permissions.
3. Put relay phones in **airplane mode, then turn Bluetooth back on** (and Wi-Fi). **Keep Location on**, because Nearby can't scan without it.
4. Set the allowlists from the Network screen: A:`B`, B:`A,C`, C:`B,G`, G:`C`. Wait until the radar shows every link before going on stage.
5. Send from A to `ravi`. The ticks progress, the server page shows the path `A>B>C>G`, Ravi's phone gets the message, and A shows ✓✓.
6. Keep a backup screen recording of a successful run.

---

## 12. Build order

| Step | Deliverable |
|---|---|
| 1 | Convert the project to Kotlin and Compose; modules `core`, `sdk`, `app`; package `app.hopnet` |
| 2 | `core`: envelope and frame codecs, sealed payloads, receipts, Router, MeshNode, in-memory store and transport, plus tests |
| 3 | Server: keys, DB, register, gateway WebSocket, hub, sys and chat services, debug page, plus pytest |
| 4 | `core`: GatewayClient, HopnetClient, ServerApi, plus the live integration test against the server |
| 5 | `sdk`: SodiumCrypto, Keystore identity, Room store, NearbyTransport, ConnectivityMonitor, MeshService, Hopnet facade |
| 6 | `app`: theme, onboarding and permissions, Home with service catalog, Messages (list, chat), Network radar |
| 7 | On-device test with 2 phones, then the chain; tune timeouts |
| next | E2E encryption for chat, per-link rate limits, boot restart, gateway gradient, **Food service** |
