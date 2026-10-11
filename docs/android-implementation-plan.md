# Hopnet: Android Implementation Plan

> Hopnet lets people with no internet use online services (food orders, tickets, SOS). A request is sealed into a small envelope, hops phone-to-phone over Nearby Connections, and is uploaded by the first phone that has internet (a "blind postman"). Replies come back the same way. SMS is the fallback.

---

## 0. Starting point and first changes

The repo is an Android Studio **"Empty Views Activity" template in Java**: `com.example.hopnet`, AppCompat and ConstraintLayout, AGP 8.13.2, Gradle 8.13, compileSdk/targetSdk 36, minSdk 26.

The pitch specifies Kotlin and Compose, so the first task is converting the project:

1. Delete `MainActivity.java`, `activity_main.xml` and the AppCompat/ConstraintLayout dependencies.
2. Add the `org.jetbrains.kotlin.android` plugin (needed on AGP 8.x), `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.x), `com.google.devtools.ksp` (for Room) and `org.jetbrains.kotlin.plugin.serialization`.
3. Rename the namespace/applicationId to a real one, for example `app.hopnet`. Play rejects `com.example.*`. Avoid `in.hopnet`, because `in` is a Kotlin keyword.
4. Keep **minSdk 26**, which covers almost all Indian Android phones, and **targetSdk 36**.
5. Bump Java to 17 (`compileOptions` and `kotlin { jvmToolchain(17) }`).

---

## 1. Architecture

### 1.1 Modules

```
Hopnet/
├── protocol/   pure Kotlin/JVM: envelope codec, frame codec, Router, store/crypto
│               interfaces, mesh SIMULATOR tests. No Android imports.
├── sdk/        Android library "hopnet-sdk": Nearby transport, Room store, MeshService,
│               gateway uplink, SMS transport, libsodium crypto, public Hopnet API
└── app/        Hopnet consumer app: Compose UI, catalog, food, SOS, tracking,
                mesh radar, demo mode. Flavors: demo | play | bridge
```

**Why a pure-Kotlin `:protocol` module:** the routing engine is the riskiest logic. Keeping it free of Android lets you run a 5- to 50-node mesh simulation in JUnit in milliseconds, without juggling phones. About 80% of mesh bugs can be found this way.

### 1.2 Layers

```
┌──────────────────────────── :app ───────────────────────────────┐
│ Compose UI · Catalog cache · Food · Tickets · SOS · Tracking     │
│ Mesh radar (demo) · Settings · Onboarding                        │
└───────────────┬─────────────────────────────────────────────────┘
                │  Hopnet.send(...) / Hopnet.updates(id) / Hopnet.status
┌───────────────▼──────────────────── :sdk ───────────────────────┐
│ HopnetClient ── RequestTracker (timeline, reply keys)            │
│      │                                                           │
│ MeshEngine (single-threaded actor, coroutines)                   │
│   ├── Router (:protocol)  flood · TTL · dedup · receipts · sync  │
│   ├── NearbyTransport     P2P_CLUSTER links, frames              │
│   ├── GatewayUplink       HTTPS batch + WebSocket (when online)  │
│   ├── SmsTransport        compact envelope over SMS (fallback)   │
│   └── EnvelopeStore       Room                                   │
│ CryptoBox (libsodium) · Identity (Keystore-wrapped keys)         │
│ MeshService (foreground service, type=connectedDevice)           │
└──────────────────────────────────────────────────────────────────┘
```

**Core principle: one envelope, three transports.** The same sealed envelope goes over Nearby (mesh), HTTPS (your own phone or any gateway is online) or SMS (compact variant). The Router does not care which transport carried it, and the server ingests it the same way, idempotently by `msgId`.

---

## 2. Protocol: the postal format

### 2.1 Outer envelope (visible to relays)

Fixed-header binary, big-endian. It is cheap to parse, and relays never decrypt anything.

| Field | Bytes | Notes |
|---|---|---|
| `magic` + `version` | 2 | `0x48` ('H'), `0x01` |
| `type` | 1 | `REQUEST`, `RESPONSE`, `RECEIPT`, `PULL`, `SOS`, `CATALOG` |
| `flags` | 1 | priority (2 bits), `DEMO_TRACE` bit |
| `msgId` | 16 | random; the dedup and idempotency key |
| `dest` | 16 | REQUEST/SOS/PULL: server key id. RESPONSE: the **reply token** (PO box) |
| `hops` | 1 | incremented by every relay (not signed) |
| `maxHops` | 1 | default 10; SOS 16 |
| `createdAt` | 4 | unix seconds |
| `expiresAt` | 4 | REQUEST 24h, SOS 72h, RESPONSE 12h |
| `bodyLen` | 2 | |
| `body` | n | opaque sealed box |
| `trace` | var | **demo builds only**, when `DEMO_TRACE` is set: relays append a 1-byte node label |

The header is 48 bytes. Relays can change `hops` and nothing else. `msgId`, `createdAt` and `expiresAt` are also copied into the signed inner payload, so the server rejects envelopes whose header has been tampered with.

### 2.2 Inner payload (only the server can read it)

```
Inner {
  msgId, createdAt, expiresAt      // must equal header values
  senderPk      : 32 B  Ed25519 identity public key
  replyToken    : 16 B  random per request  (where responses get addressed)
  replyPk       : 32 B  ephemeral X25519 public key per request (responses encrypted to it)
  service       : u8    FOOD | TICKET | SOS | ...
  body          : CBOR  service-specific (order lines, address id, note, ...)
  sig           : 64 B  Ed25519 over everything above
}
padded to a 256/512/1024-byte bucket  →  crypto_box_seal(serverX25519Pk)   (+48 B)
```

- **Sign, then seal:** the server knows who sent the request, while relays learn nothing, not even who the sender is.
- **A per-request reply token and ephemeral reply key** mean relays cannot link one user's responses across orders.
- **Padding buckets** stop relays from guessing what was ordered from the message size.
- A typical food order comes to roughly 420 bytes and pads to **512 B**, comfortably under the 1 KB budget. Nearby's `BYTES` payload limit is 32 KB.

### 2.3 RESPONSE (server to user)

`dest = replyToken`. `body = crypto_box_seal(replyPk, Signed{ requestMsgId, seq, status, fields, serverSig })`.

Statuses: `ACCEPTED(total, eta)`, `REJECTED(reason)`, `PRICE_CHANGED(newTotal)`, `PREPARING`, `OUT_FOR_DELIVERY`, `DELIVERED`, plus SOS statuses (`RECEIVED_BY_RESPONDER`, ...). `seq` is monotonic per request, and the app keeps the highest one.

### 2.4 RECEIPT: the "vaccine" that stops the flood

When the server ingests a request, it issues `RECEIPT{ requestMsgId, hopsAtArrival, serverSig }`. The receipt is signed but not encrypted. Every relay:

1. Verifies the server signature against the pinned server key. Without this check, a forged receipt could kill real orders.
2. Deletes its stored copy of the request and stops forwarding it.
3. Forwards the receipt.

Without receipts, every phone keeps re-broadcasting a delivered order until it expires. Receipts are also what the sender's tracking screen uses to show "Reached Hopnet server · 4 hops".

### 2.5 PULL: checking the PO box (stretch goal)

A phone with open requests sends `PULL{ replyTokens[] }` toward the server. A gateway forwards it, and the server returns any queued responses, which the gateway injects into the mesh. This covers the case where the gateway that carried the request has gone and the push in section 4.3 missed the user.

### 2.6 SOS

SOS uses the same pipeline at the highest priority, with `maxHops 16`, a 72-hour expiry and a GPS fix (GNSS works with no data). An optional opt-in cleartext "someone nearby needs help" beacon can be shown on other Hopnet phones in range.

### 2.7 CATALOG gossip (stretch goal)

Server-signed menu snapshots are shared phone-to-phone as Nearby `FILE` payloads, so a phone that never went online can still get menus. Relays verify the signature before storing or showing a snapshot.

---

## 3. Mesh layer (Nearby Connections)

### 3.1 Configuration

| Setting | Value | Why |
|---|---|---|
| Strategy | `Strategy.P2P_CLUSTER` | The only strategy that allows M-to-N mesh. It is the **lower-bandwidth Bluetooth strategy**: the automatic upgrade to Wi-Fi applies to STAR and POINT_TO_POINT. That is fine for 0.5 KB envelopes. **Fix the pitch line** about automatically picking the faster of Bluetooth and Wi-Fi Direct. |
| `serviceId` | `"hopnet.mesh.v1"` | Must be identical in **every app that embeds the SDK**, so they all join one mesh. |
| Advertise + discover | Both, always (duty-cycled when idle, see section 8) | |
| `endpointInfo` | `[protoVer, flags(isGateway, hasPending), gatewayDist, nodeId(4B, rotates daily)]`, kept ≤ 16 B | Lets a phone choose whom to connect to **before** connecting. Changing it requires stop/start advertising. |
| Accept | Auto-accept, no PIN | Security comes from end-to-end crypto, not link authentication. |

### 3.2 ConnectionManager

- **At most 4 concurrent links.** Bluetooth gets unstable beyond a few links.
- On `onEndpointFound`, score the endpoint and connect if a slot is free or it beats the worst current link:
  `score = 100·isGateway + 10·(255 − gatewayDist)/255 + 20·notSyncedRecently − 50·recentFailures`
- **Collision rule:** when both sides discover each other, only the side with the lower `nodeId` calls `requestConnection`. The other side waits about 5 s before trying itself.
- **Back-off:** exponential per endpoint on `STATUS_RADIO_ERROR`, `STATUS_ENDPOINT_IO_ERROR` and similar errors.
- **Rotation:** if a link has been idle and fully synced for 60 s while other discovered endpoints are unsynced, drop it to free the slot. This is what spreads messages through a crowd.
- **Demo allowlist (section 12):** when set, connect to and accept only the listed neighbors.

### 3.3 Link frames (Nearby `BYTES` payloads)

```
Frame = type:u8 + body
  HELLO   { nodeId, isGateway, gatewayDist, inventory: [8-byte msgId prefixes, newest 256] }
  WANT    { msgId prefixes }
  ENV     { envelope bytes }
  RCPT    { receipt bytes }
  PING
```

**On connect (epidemic sync):** both sides send HELLO, then WANT what they are missing, then send ENV and RCPT frames, highest priority first: SOS > RCPT > RESPONSE > REQUEST > PULL > CATALOG.
**While connected:** new envelopes are pushed to every link except the one they arrived on, and receivers deduplicate.
**MVP shortcut:** skip WANT and send everything in the store on connect. Stores hold tens of tiny messages, so this is fine for the demo.

### 3.4 Router rules (`:protocol`, pure Kotlin)

```kotlin
fun onEnvelope(env: Envelope, from: LinkId) {
    if (seen.contains(env.msgId)) return                 // dedup
    seen.add(env.msgId)
    if (env.isExpired(now) || receipts.has(env.msgId)) return
    if (env.type == RECEIPT) { applyReceipt(env); forward(env, except = from); return }
    if (env.type == RESPONSE && replyTokens.isMine(env.dest)) { deliverToApp(env); return }
    if (isGateway && env.type in UPLINK_TYPES) uplink.enqueue(env)   // keep flooding until RECEIPT
    store.put(env, origin = RELAY)
    if (env.hops + 1 < env.maxHops) forward(env.withHops(env.hops + 1), except = from)
}
```

### 3.5 Store policy and abuse limits

- **Caps:** 2,000 relayed envelopes or 5 MB. **Your own messages are never evicted** until a receipt arrives or they expire.
- **Eviction order:** expired first, then messages with receipts, then the lowest-priority oldest.
- **Seen table:** pruned after 7 days, with an in-memory LRU of 10k entries in front of Room.
- **Per-link limits:** at most 50 envelopes per minute, at most 2 KB per envelope, and malformed frames are dropped. A link that keeps misbehaving is disconnected and blocked for 10 minutes.

### 3.6 Gateway gradient (stretch goal)

`gatewayDist = 0` on gateways, otherwise `min(neighbors) + 1`, decaying to 255 if not refreshed for 2 minutes. It is used only to prioritize which links to connect to and send to first. Flooding still happens, for robustness.

---

## 4. Gateway mode (the blind postman)

1. **Detect internet:** `ConnectivityManager.registerDefaultNetworkCallback`, requiring `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`, debounced for 5 s, then confirmed with `GET /v1/health` to catch captive portals.
2. **Change role:** restart advertising with `isGateway = 1` and `gatewayDist = 0`.
3. **Uplink:** batch `REQUEST`/`SOS`/`PULL` envelopes, then `POST /v1/relay/batch` (`application/octet-stream`, length-prefixed). The response contains RECEIPTs, which are injected into the mesh.
4. **Downlink:** open a WebSocket `/v1/relay/ws` while in gateway role. The server pushes RESPONSE and RECEIPT envelopes, which are injected with `Router.onEnvelope(env, from = UPLINK)`.
5. **The same path covers your own phone being online.** Its own requests go straight up, and it fetches its own responses through `POST /v1/mailbox {tokens}`.
6. **Consent and budget:** onboarding opt-in ("Help neighbours: relay up to 20 MB/day"), a Wi-Fi-only toggle and a counter in Settings. Real usage is tiny, at about 0.5 KB per envelope.
7. **Blindness:** the gateway sends no information about who originated a message. It is just a pipe for opaque bytes.

OkHttp with WebSockets, and certificate pinning for the Hopnet server domain.

---

## 5. SMS fallback

**Trigger:** a request is still pending, there have been no mesh peers for 2 minutes (15 s in demo mode) or the user taps "Send via SMS now", there is no internet, and the phone has cellular service. Always ask first, because SMS can cost money.

**Compact format.** A 512 B envelope would need about 9 SMS segments in base64. Instead:

```
"HN1 " + base64url( keyId:8 | nonce:24 | crypto_box(compactPayload, senderX25519Sk, serverX25519Pk) )
compactPayload ≈ service:1 | restaurantId:varint | [itemId:varint, qty:u8]* | addressId:varint | msgId:8 | ts:4
```

- `crypto_box` (sender-authenticated) replaces sign-then-seal, saving the 64 B signature and 32 B public key. The server looks up the sender's key by `keyId`, which was registered while online. Sending `keyId` in clear is fine here, because the telco already knows the phone number.
- Result: about 90 bytes, roughly 120 base64 characters, which fits in **one SMS segment**.

**Sending, by flavor:**
- `demo`/`bridge` flavors: `SmsManager.sendMultipartTextMessage` with `SEND_SMS`.
- `play` flavor: Google Play restricts `SEND_SMS`/`RECEIVE_SMS` to default SMS apps, so use `Intent(ACTION_SENDTO, "smsto:<number>")` prefilled, and the user taps Send.

**Replies:** the server sends a human-readable SMS ("Hopnet: Order A7 accepted, ₹340 COD, ETA 35 min") with a machine tag. The app ingests it via the **SMS User Consent API** (no permission needed) or `RECEIVE_SMS` in the demo flavor.

**Server side:** for the hackathon, use a **`bridge` flavor of this same app** on a spare phone with a SIM. It forwards inbound SMS to `POST /v1/sms/inbound` and sends replies. In production you would need an Indian long-code/virtual-number provider, and outbound A2P SMS in India requires DLT template registration.

---

## 6. Crypto and identity

**Library:** libsodium through **Lazysodium-android** (`com.goterl:lazysodium-android` and `net.java.dev.jna:jna` as `@aar`; check the latest versions). Hide it behind a `Crypto` interface in `:protocol`, and use `lazysodium-java` in JVM tests. Only use libsodium primitives (`crypto_sign`, `crypto_box_seal`, `crypto_box`), never your own constructions. Bridgefy's mesh SDK had its security broken by academic researchers, and judges may know that story.

| Key | Type | Where |
|---|---|---|
| User identity | Ed25519 (and X25519 derived via `crypto_sign_ed25519_*_to_curve25519` for the SMS `crypto_box`) | Generated on first launch. The secret key is encrypted with an **AES-256-GCM key in Android Keystore** and stored in app files. Jetpack `EncryptedSharedPreferences` is deprecated, so don't use it. |
| Server encryption | X25519 public key | Pinned in `BuildConfig` |
| Server signing | Ed25519 public key | Pinned. It verifies every RESPONSE, RECEIPT and CATALOG. |
| Per request | Ephemeral X25519 reply keypair and 16 B reply token | Room (`pending_reply`), secret key wrapped with the Keystore key, deleted when the request ends |

**Registration (online, at onboarding):** phone OTP, then `POST /v1/register {ed25519Pk, phone}`, which returns `{userId, keyId}`. This ties every cash-on-delivery order to a verified number, which is the main defense against prank orders.

**Server obligations the app relies on:**
- **Idempotency on `msgId`**: the same order **will** arrive through several gateways. Third-party documentation for Swiggy MCP says orders can't be cancelled once placed, so a duplicate is unrecoverable.
- Reject inner/header mismatches, and treat `createdAt` older than about 48 h as stale: reject it or ask for re-confirmation.
- Sign every downstream message.

Nearby's own link encryption is hop-to-hop only and does not replace any of this.

---

## 7. Data layer (Room)

**SDK database `hopnet_mesh.db`:**

| Table | Columns (key ones) |
|---|---|
| `envelope` | `msgId PK`, type, dest, priority, hops, maxHops, createdAt, expiresAt, `bytes BLOB`, origin (LOCAL/RELAY), uploadedAt. Indexed on `expiresAt` and `dest`. |
| `seen` | `msgId PK`, firstSeenAt |
| `receipt` | `msgId PK`, bytes, hopsAtArrival, receivedAt |
| `pending_reply` | `replyToken PK`, requestMsgId, wrappedReplySk, expiresAt |
| `outbound_request` | requestId PK, msgId, service, channel (MESH/DIRECT/SMS), state, createdAt |
| `request_event` | requestId, state, at, detail (drives the tracking timeline) |
| `peer_stat` | nodeId, label, lastSeen, isGateway, gatewayDist, envIn, envOut (drives the mesh radar) |

**App database `hopnet_app.db`:** `restaurant`, `menu_item`, `catalog_meta(version, fetchedAt, sig)`, `order`, `saved_address`, `sos_profile`.

Settings and the identity blob go in DataStore and files.

---

## 8. Background execution and battery

- **`MeshService`** is a `LifecycleService` with `android:foregroundServiceType="connectedDevice"` and permissions `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Its ongoing notification reads "Hopnet active · 3 nearby · 1 waiting". On Android 14+, the Nearby/Bluetooth runtime permissions must already be granted before `startForeground`, or it throws.
- **Start triggers:** the app is opened, own messages are pending, or the "Relay for neighbours" setting is on.
- **Survival:**
  - A `BOOT_COMPLETED` receiver. `connectedDevice` is **not** among the foreground service types Android 15 blocks from boot.
  - `START_STICKY`.
  - Asking the user for the **battery-optimization exemption**. This also exempts the app from Android 12+ background foreground-service start limits, so a WorkManager watchdog can restart the service.
- **OEM battery killers** (Xiaomi/Redmi, Vivo, Oppo, Realme, OnePlus, which dominate Indian phones) kill relays without warning. Onboarding needs an OEM-specific "allow autostart / no restrictions" screen with deep links (follow dontkillmyapp.com patterns).
- **Duty cycling:** when idle (nothing pending, not a gateway), advertise continuously at low cost and discover 20 s on, 40 s off. When something is pending, on a gateway, or in demo mode, discover continuously.
- **Engine threading:** `MeshEngine` runs as a single-coroutine actor consuming a `Channel<EngineEvent>`. Nearby callbacks, uplink results and app sends all become events, so the routing state needs no locks.

---

## 9. Permissions

```xml
<!-- Nearby Connections -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/> <!-- Nearby on ≤12, SOS GPS on all -->
<!-- Service / network -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<!-- demo + bridge flavors only (src/demo/AndroidManifest.xml) -->
<uses-permission android:name="android.permission.SEND_SMS"/>
<uses-permission android:name="android.permission.RECEIVE_SMS"/>
```

Ask for runtime permissions inside onboarding, with a one-line "why" for each. On Android ≤ 12, **Location services must be switched on** for Bluetooth scanning to work. Put this on the demo checklist.

---

## 10. SDK public API (what partner apps call)

```kotlin
// Host app: Application.onCreate()
Hopnet.init(context, HopnetConfig(
    serverUrl      = "https://api.hopnet.app",
    serverEncPk    = BuildConfig.SERVER_ENC_PK,
    serverSignPk   = BuildConfig.SERVER_SIGN_PK,
    smsNumber      = "+91XXXXXXXXXX",          // optional fallback
    notification   = NotificationConfig(channelName = "Offline delivery"),
))
Hopnet.start()

// Send a request (works online, offline-mesh, or SMS)
val handle: RequestHandle = Hopnet.send(service = "food", body = cborBytes, priority = Priority.NORMAL)

// Observe it
Hopnet.updates(handle.id).collect { u ->
    when (u) {
        is RequestUpdate.Queued         -> ...
        is RequestUpdate.HandedToPeers  -> u.count
        is RequestUpdate.ServerReceived -> u.hops
        is RequestUpdate.Response       -> u.payload   // decrypted + verified
        is RequestUpdate.SentViaSms     -> ...
        is RequestUpdate.Expired        -> ...
    }
}

Hopnet.status: StateFlow<MeshStatus>   // peers, isGateway, gatewayDist, queued, relayedToday
Hopnet.setRelayEnabled(true)
```

**SDK rules:**
- No Hilt and no Compose inside `:sdk`; use manual DI so it can be dropped into any app.
- Ship `consumer-rules.pro` for JNA and Lazysodium.
- All public calls are main-safe.
- **Several SDK apps on one phone** would each try to run a mesh service. For the hackathon, just document it. Later, elect one host per device through a shared exported ContentProvider or bound service.

---

## 11. App screens (Compose, Material 3)

The app uses MVVM with `StateFlow`, Navigation Compose, Hilt (app module only), Room and DataStore.

| # | Screen | Notes |
|---|---|---|
| 1 | **Onboarding** (online) | Welcome, phone OTP and key registration, permissions, battery/autostart, "Relay for neighbours" opt-in, catalog download |
| 2 | **Home** | Status pill: `Online` / `Offline · 3 phones nearby · gateway 2 hops` / `Alone · SMS available`. Tiles: Food, Tickets, SOS, My requests. |
| 3 | **Restaurants, Menu, Cart, Checkout** | Cached data with a "Menu as of 2 h ago" note. Saved address, cash on delivery, and a "Final price confirmed by restaurant" line. Optional "auto-accept price changes up to ±10%" checkbox, which saves a second round trip. |
| 4 | **Tracking** (hero screen) | Postal-style timeline: *Sealed (512 B, encrypted)*, *Handed to 2 nearby phones*, *Reached Hopnet · 4 hops*, *Accepted · ₹340 COD · ETA 35 min*, *Out for delivery*, *Delivered*. Channel chip (Mesh/Direct/SMS) and a "Send via SMS instead" button. |
| 5 | **SOS** | Hold for 3 s to send, type picker (medical/fire/trapped/other), automatic GPS, optional note, saved medical profile. Same timeline. |
| 6 | **Tickets** | Simple form (route, date, seats) through the same pipeline against a mock adapter. Stretch goal. |
| 7 | **Mesh radar** (demo star) | Live graph of peers (gateway badge, hop distance), envelope counters and a scrolling log ("→ relayed 7f3a to C (hop 2)"). Mirror it with scrcpy on the projector. |
| 8 | **Settings** | Relay on/off, gateway data budget, Wi-Fi-only, SMS fallback, demo mode, "reset mesh store" |

---

## 12. Demo mode (critical for the live demo)

**Problem:** in a hackathon hall, every phone is within Bluetooth range of every other phone. Nearby would connect the sender **directly to the gateway in one hop**, and the hop-by-hop story would disappear on stage.

**Fix:**
- **Topology lock:** each phone gets a label (A, B, C, D, G) and a neighbor allowlist forming a chain A↔B↔C↔D↔G. ConnectionManager only connects to and accepts allowlisted endpoints. Configure it from a QR code on a settings screen.
- **Trace:** with the `DEMO_TRACE` flag, every relay appends its label. The receipt carries the path back, so the phone shows "A→B→C→D→G→server" and the restaurant dashboard can show it too.
- **Shorter timers:** the SMS fallback timeout drops from 2 min to 15 s, and discovery runs continuously.

**Rehearsal checklist:**
- [ ] Airplane mode on, then **manually turn Bluetooth and Wi-Fi back on**. Location on.
- [ ] Gateway phone on mobile data, with a second hotspot as backup.
- [ ] Open the app on every phone **5 minutes before** going on stage and confirm all links on the radar. Nearby discovery and connection take seconds per hop, so never do cold discovery live.
- [ ] Google Play services up to date on every phone; battery optimization off; "Stay awake" developer option on; phones charged.
- [ ] Use "Reset mesh store" between rehearsals.
- [ ] SMS segment: switch off Bluetooth on the sender; after 15 s the SMS goes out, the bridge phone receives it, and the order appears on the dashboard.
- [ ] Record a backup video of a successful run.

---

## 13. Server contract the app depends on

The server team builds it in FastAPI; this is the interface the app needs.

| Endpoint | Purpose |
|---|---|
| `POST /v1/register` | `{ed25519Pk, phone, otp}` returns `{userId, keyId}` |
| `GET /v1/catalog?since=v` | Signed menu snapshot or delta |
| `POST /v1/relay/batch` | Raw envelopes in; RECEIPTs out. **Idempotent on `msgId`.** |
| `WS /v1/relay/ws` | Server pushes RESPONSE and RECEIPT envelopes to gateways |
| `POST /v1/mailbox` | `{replyTokens[]}` returns queued RESPONSE envelopes (direct/PULL path) |
| `POST /v1/sms/inbound` | From the bridge phone: `{from, text}` |
| `GET /v1/health` | Captive-portal check |

**Share golden test vectors on day 1.** Put a `test-vectors/` folder in the repo with envelope hex, keys and the expected decryption. Kotlin's `crypto_box_seal` against Python's PyNaCl `SealedBox` is the most likely integration bug.

---

## 14. Testing

- **`:protocol` unit tests:** codec round-trips, golden vectors, Router rules (dedup, TTL, receipts purge, expiry, rate limits).
- **Mesh simulator (JVM):** `InMemoryTransport` with a virtual clock. Scenarios:
  - (a) a line of 5 phones;
  - (b) 50 phones with random contacts;
  - (c) a partitioned mesh that a "rider" gateway walks through.

  Assert: delivered, **exactly one** ingest at the fake server, and stores purged after receipts.
- **Device tests:**
  - 2-phone link, then a 3-phone line, then 5 phones;
  - gateway flip (toggle Wi-Fi);
  - force-stop and reboot;
  - Doze (`adb shell dumpsys deviceidle force-idle`);
  - at least one Xiaomi/Vivo/Realme phone.
- **Metrics:** log connect latency, per-hop latency and end-to-end time. **Measure the numbers you put on slides; don't estimate them.**

---

## 15. Build order

Each milestone is demoable on its own, and anything above the cut line is needed for the live demo.

| # | Milestone | Done when |
|---|---|---|
| M0 | Convert to Kotlin/Compose; create `:protocol` and `:sdk`; add dependencies | Empty Compose app builds; modules wired |
| **M1** | **Nearby "hello" between 2 phones** (de-risk first) | Bytes sent A→B in airplane mode |
| M2 | Envelope codec, Router, Room store, dedup/TTL, demo allowlist, basic radar | Plaintext envelope crosses A→B→C→D; simulator tests green |
| M3 | Gateway: NetworkCallback, batch upload, stub server returns RECEIPT | Envelope reaches the laptop; receipt flows back and purges stores |
| M4 | Crypto: identity, sign+seal, reply keys, Kotlin↔Python vectors | Server decrypts and verifies; relays see only ciphertext |
| M5 | App flow: onboarding (lite), catalog sync, menu, cart, checkout, tracking | Real order JSON reaches the server through the mesh |
| M6 | Downlink: WebSocket push, mesh flood to reply token, decrypt, timeline | "Accepted" and "Out for delivery" appear on the sender phone |
| ─ | **Demo cut line** | 5-phone run works end to end |
| M7 | MeshService hardening, boot and battery handling, OEM screen, SOS screen | Survives screen-off for 30 min |
| M8 | SMS fallback (demo flavor) and bridge flavor | Order lands via SMS with no mesh |
| M9 | Polish: QR demo setup, radar animation, trace path, metrics, backup video | Rehearsed 3× without failure |
| S | Stretch: gateway gradient, PULL mailbox, catalog gossip, Tickets, "integrate in 3 lines" sample app | |

**Suggested split for three Android developers:**
- Dev 1: Nearby and Router (M1, M2, M7).
- Dev 2: crypto, gateway and SMS (M3, M4, M8).
- Dev 3: Compose UI and radar (M5, M6 UI, M9).

The server developer works in parallel on FastAPI, adapters and the dashboard, against section 13.

---

## 16. Dependencies to add (`libs.versions.toml`)

Use the latest stable versions from Android Studio's suggestions:

- Kotlin 2.2+, KSP, Compose BOM, `material3`, `activity-compose`, `navigation-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `lifecycle-service`
- `com.google.android.gms:play-services-nearby`
- `play-services-location` (SOS GPS)
- `androidx.room` (runtime, ktx, compiler via KSP), `androidx.datastore:datastore-preferences`
- `kotlinx-coroutines-android`, `kotlinx-serialization-json` and `kotlinx-serialization-cbor`
- `okhttp` (includes WebSocket)
- `com.goterl:lazysodium-android` and `net.java.dev.jna:jna` (`@aar`); `com.goterl:lazysodium-java` for JVM tests
- Hilt (app only), `androidx.work:work-runtime-ktx`
- Tests: JUnit, Turbine, `kotlinx-coroutines-test`, Robolectric (optional)

---

## 17. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Nearby discovery or connections are flaky and slow | Demo fails | Pre-warm, allowlist topology, back-off/retry, backup video |
| OEM battery killers | Relays silently die | Foreground service, battery exemption, OEM autostart guidance, boot receiver |
| Duplicate ingest from multi-path flooding | Double orders, which Swiggy MCP reportedly can't cancel | Server idempotency on `msgId`; receipts |
| Stale cached prices | Cash-on-delivery disputes | Server confirms the final price in ACCEPTED; PRICE_CHANGED needs confirmation (or uses the ±10% auto-accept) |
| Prank cash-on-delivery orders | Restaurant losses | OTP-bound keys, per-user limits, caps for new users |
| Mesh spam or flooding | Battery and storage drain | Per-link rate limits, size caps, TTL, signed receipts |
| Play policy (SMS permissions, foreground service type declaration) | Store rejection | Flavors, `ACTION_SENDTO`, documented `connectedDevice` use |
| Sparse mesh | No path out | Store-and-forward, SMS fallback, SDK distribution |
