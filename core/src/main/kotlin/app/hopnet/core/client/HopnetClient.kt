package app.hopnet.core.client

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.crypto.Identity
import app.hopnet.core.crypto.ServerKeys
import app.hopnet.core.mesh.MeshNode
import app.hopnet.core.protocol.BroadcastInner
import app.hopnet.core.protocol.Broadcasts
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.protocol.Ids
import app.hopnet.core.protocol.ReceiptStatus
import app.hopnet.core.protocol.SealedPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns service-level messages ("chat"/"message", later "food"/"order"...) into sealed,
 * signed UP envelopes, and verified DOWN envelopes back into [IncomingMessage]s.
 * [broadcast] is the serverless path: a signed message every nearby phone can read
 * ([broadcasts]), which gateways also upload to the server.
 * Also runs the two system duties every service relies on:
 *  - sys/poll: while offline but near other phones, tell the server where we are (PO box)
 *  - sys/ack:  confirm every DOWN we receive so the server marks it delivered
 */
class HopnetClient(
    private val node: MeshNode,
    private val crypto: HopCrypto,
    val identity: Identity,
    private val server: ServerKeys,
    parentScope: CoroutineScope,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Config(
        val pollIntervalMs: Long = 30_000,
        val pollOnNewLinkAfterMs: Long = 8_000,
        val pollTtlMs: Long = 90_000,
        val ackDelayMs: Long = 700,
        val ackTtlMs: Long = 6 * HOUR,
        val defaultTtlMs: Long = 24 * HOUR,
        val maxHops: Int = Envelope.DEFAULT_MAX_HOPS,
        val broadcastTtlMs: Long = 12 * HOUR,
        val broadcastMaxHops: Int = Broadcasts.DEFAULT_MAX_HOPS,
    )

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 256)
    val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()
    private val _updates = MutableSharedFlow<OutgoingUpdate>(extraBufferCapacity = 256)
    val updates: SharedFlow<OutgoingUpdate> = _updates.asSharedFlow()
    private val _broadcasts = MutableSharedFlow<IncomingBroadcast>(extraBufferCapacity = 256)
    /** Verified broadcasts from other phones nearby, e.g. Find Me alerts. No server involved. */
    val broadcasts: SharedFlow<IncomingBroadcast> = _broadcasts.asSharedFlow()

    private val internalIds = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 500
    }
    /**
     * Broadcasts we sent. A newer update replaces an older one in our store, so a late receipt
     * for the older one no longer counts as "mine" there; this remembers them anyway.
     */
    private val ownBroadcasts = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 500
    }
    private var lastBroadcastAt = 0L
    private val ackMutex = Mutex()
    /** DOWN msgId -> (path it took to reach us, hops), reported back so the server can show it. */
    private val pendingAcks = LinkedHashMap<String, Pair<String, Int>>()
    private var ackJob: Job? = null

    fun start() {
        node.setIdentity(identity.address, server.signPk, crypto)
        scope.launch { node.deliveries.collect { onDown(it) } }
        scope.launch { node.broadcasts.collect { onBroadcast(it) } }
        scope.launch {
            node.receipts.collect { (receipt, mine) ->
                if (isInternal(receipt.targetId)) return@collect
                if (!mine && !isOwnBroadcast(receipt.targetId)) return@collect
                when (receipt.status) {
                    ReceiptStatus.RECEIVED -> _updates.emit(OutgoingUpdate.AtServer(receipt.targetId, receipt.hops, receipt.path))
                    ReceiptStatus.REJECTED -> _updates.emit(OutgoingUpdate.Rejected(receipt.targetId))
                    ReceiptStatus.DELIVERED -> Unit
                }
            }
        }
        scope.launch {
            node.ownSent.collect { if (!isInternal(it.msgId)) _updates.emit(OutgoingUpdate.Forwarded(it.msgId, it.peers, it.viaUplink)) }
        }
        scope.launch { pollLoop() }
    }

    fun close() {
        job.cancel()
    }

    /**
     * Sends a service request toward the server through whatever path exists: straight up if
     * this phone is online, otherwise hop by hop until some phone with internet carries it.
     */
    suspend fun send(
        service: String,
        kind: String,
        data: JsonObject,
        msgId: String = Ids.newMsgId(),
        ttlMs: Long = config.defaultTtlMs,
    ): String {
        val now = clock()
        val inner = UpInner(id = msgId, from = identity.address, ts = now, exp = now + ttlMs, svc = service, kind = kind, data = data)
        val json = HopnetJson.encodeToString(UpInner.serializer(), inner).toByteArray()
        val body = SealedPayload.seal(json, identity.sign.secretKey, server.encPk, crypto)
        node.originate(
            Envelope(
                type = EnvelopeType.UP,
                flags = Envelope.FLAG_TRACE,
                msgId = msgId,
                dest = Ids.ZERO,
                hops = 0,
                maxHops = config.maxHops,
                createdAt = now,
                expiresAt = now + ttlMs,
                body = body,
            )
        )
        return msgId
    }

    /**
     * Sends a signed message to every phone nearby, hop by hop, with no server needed. Each new
     * broadcast replaces this user's previous one everywhere. Any gateway on the way also uploads
     * it to the server ([updates] reports AtServer when it got there).
     */
    suspend fun broadcast(
        service: String,
        kind: String,
        data: JsonObject,
        msgId: String = Ids.newMsgId(),
        ttlMs: Long = config.broadcastTtlMs,
    ): String {
        // Strictly increasing per sender, so "newest wins" holds even for two sends in one ms, or
        // when the clock was set back after a restart (else every phone would ignore our updates).
        val floor = node.latestBroadcastAt(identity.address) ?: 0L
        val now = synchronized(ownBroadcasts) {
            maxOf(clock(), lastBroadcastAt + 1, floor + 1).also {
                lastBroadcastAt = it
                ownBroadcasts[msgId] = Unit
            }
        }
        val inner = BroadcastInner(id = msgId, from = identity.address, ts = now, exp = now + ttlMs, svc = service, kind = kind, data = data)
        node.originate(Broadcasts.build(inner, identity.sign, crypto, config.broadcastMaxHops))
        return msgId
    }

    private fun isOwnBroadcast(msgId: String) = synchronized(ownBroadcasts) { msgId in ownBroadcasts }

    private suspend fun onBroadcast(env: Envelope) {
        val inner = Broadcasts.open(env, crypto) ?: return
        if (inner.from == identity.address) return
        _broadcasts.emit(IncomingBroadcast(env.msgId, inner.from, inner.svc, inner.kind, inner.data, inner.ts, inner.exp, env.hops, env.trace))
    }

    private suspend fun sendInternal(kind: String, data: JsonObject, ttlMs: Long) {
        val id = Ids.newMsgId()
        synchronized(internalIds) { internalIds[id] = Unit }
        send(SYS, kind, data, id, ttlMs)
    }

    private fun isInternal(msgId: String) = synchronized(internalIds) { msgId in internalIds }

    private suspend fun onDown(env: Envelope) {
        val json = SealedPayload.openAndVerify(env.body, identity.box.publicKey, identity.box.secretKey, server.signPk, crypto)
            ?: return
        val inner = runCatching { HopnetJson.decodeFromString(DownInner.serializer(), json.decodeToString()) }.getOrNull() ?: return
        if (inner.id != env.msgId || inner.to != identity.address || inner.exp != env.expiresAt) return
        scheduleAck(env.msgId, env.trace.joinToString(">"), env.hops)
        _incoming.emit(IncomingMessage(env.msgId, inner.svc, inner.kind, inner.data, inner.ts, env.hops, env.trace))
    }

    private suspend fun scheduleAck(msgId: String, path: String, hops: Int) {
        ackMutex.withLock {
            pendingAcks[msgId] = path to hops
            if (ackJob?.isActive == true) return
            ackJob = scope.launch {
                delay(config.ackDelayMs)
                val batch = ackMutex.withLock { pendingAcks.toMap().also { pendingAcks.clear() } }
                if (batch.isNotEmpty()) sendInternal("ack", ackPayload(batch), config.ackTtlMs)
            }
        }
    }

    /** {"ids": [...], "trips": [{"id", "path", "hops"}]}: trips are informational (debug page). */
    private fun ackPayload(batch: Map<String, Pair<String, Int>>) = JsonObject(
        mapOf(
            "ids" to JsonArray(batch.keys.map { JsonPrimitive(it) }),
            "trips" to JsonArray(
                batch.map { (id, trip) ->
                    JsonObject(mapOf("id" to JsonPrimitive(id), "path" to JsonPrimitive(trip.first), "hops" to JsonPrimitive(trip.second)))
                }
            ),
        )
    )

    /** "I'm here": lets the server route our pending mail to whichever gateway carries this. */
    private suspend fun pollLoop() {
        var lastPoll = 0L
        var lastPeers = 0
        while (scope.isActive) {
            delay(1_000)
            val peers = node.peers.value.size
            val now = clock()
            if (!node.isGateway.value && peers > 0) {
                val since = now - lastPoll
                if (since >= config.pollIntervalMs || (peers > lastPeers && since >= config.pollOnNewLinkAfterMs)) {
                    lastPoll = now
                    sendInternal("poll", JsonObject(emptyMap()), config.pollTtlMs)
                }
            }
            lastPeers = peers
        }
    }

    companion object {
        const val SYS = "sys"
        private const val HOUR = 60L * 60 * 1000
    }
}
