package app.hopnet.core.mesh

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.protocol.Broadcasts
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.protocol.Receipt
import app.hopnet.core.protocol.Receipts
import app.hopnet.core.store.EnvelopeStore

interface RouterOutput {
    fun sendToLink(linkId: String, envelope: Envelope)
    fun uplink(envelopes: List<Envelope>)
    fun deliverLocal(envelope: Envelope)
    /** A verified BROADCAST from someone else, newer than anything we held from them. */
    fun deliverBroadcast(envelope: Envelope)
    fun onReceipt(receipt: Receipt, mine: Boolean)
    fun onOwnSent(msgId: String, peers: Int, viaUplink: Boolean)
    fun log(message: String)
}

data class RouterStats(
    val received: Int = 0,
    val duplicates: Int = 0,
    val dropped: Int = 0,
    val forwarded: Int = 0,
    val uploaded: Int = 0,
    val deliveredHere: Int = 0,
    val receipts: Int = 0,
    val broadcasts: Int = 0,
)

/**
 * The hopping rules: controlled flooding with hop limit, duplicate filtering, store-and-forward
 * on every new link, gateway custody, and signed receipts that purge delivered copies.
 * BROADCASTs are the exception: every phone shows them, keeps only each sender's newest one,
 * and keeps flooding it after the server has it. Gateways both upload and flood them.
 * Not thread-safe by design: [MeshNode] drives it from a single coroutine.
 */
class Router(
    private val store: EnvelopeStore,
    private val output: RouterOutput,
    private val clock: () -> Long,
    crypto: HopCrypto? = null,
) {
    var label: String = "node"
    var myAddress: String? = null
    private var serverSignPk: ByteArray? = null
    /** Verifies broadcasts (always, also before registration) and, with [serverSignPk], receipts. */
    private var crypto: HopCrypto? = crypto

    var isGateway: Boolean = false
        private set
    var stats = RouterStats()
        private set

    private val links = LinkedHashSet<String>()
    /** Own msgId -> links it has been handed to (for "passed to N phones"). */
    private val ownSends = HashMap<String, MutableSet<String>>()

    fun setReceiptVerifier(serverSignPk: ByteArray?, crypto: HopCrypto?) {
        this.serverSignPk = serverSignPk
        if (crypto != null) this.crypto = crypto
    }

    // ---- inputs -------------------------------------------------------------------------

    suspend fun originate(envelope: Envelope) {
        val now = clock()
        store.markSeen(envelope.msgId, now)
        val env = envelope.stamped(label)
        val broadcast = env.type == EnvelopeType.BROADCAST
        if (broadcast) supersede(env, now)
        store.put(env, local = true, now = now)
        output.log("new ${env.type} ${env.shortId} (${env.body.size} B ${if (broadcast) "signed" else "sealed"})")
        if (env.type == EnvelopeType.UP && isGateway) {
            upload(listOf(env), localIds = setOf(env.msgId))
        } else {
            flood(env, except = null, local = true)
            if (broadcast && isGateway) upload(listOf(env), localIds = setOf(env.msgId))
            if (links.isEmpty() && !isGateway) output.log("no neighbours: ${env.shortId} waits in store")
        }
    }

    suspend fun onLinkUp(linkId: String) {
        links += linkId
        val items = store.live(clock(), SYNC_LIMIT).filter { it.envelope.hops < it.envelope.maxHops }
        // A gateway keeps UP envelopes for the server, no point pushing them back into the mesh.
        val toSend = if (isGateway) items.filter { it.envelope.type != EnvelopeType.UP } else items
        toSend.forEach { send(linkId, it.envelope, it.local) }
        if (toSend.isNotEmpty()) output.log("sync → $linkId: ${toSend.size} stored envelope(s)")
    }

    fun onLinkDown(linkId: String) {
        links -= linkId
    }

    suspend fun onFromLink(envelope: Envelope, linkId: String) {
        val now = clock()
        stats = stats.copy(received = stats.received + 1)
        val problem = validate(envelope, now) ?: if (envelope.hops >= envelope.maxHops) "hop limit" else null
        if (problem != null) {
            stats = stats.copy(dropped = stats.dropped + 1)
            output.log("drop ${envelope.shortId}: $problem")
            return
        }
        if (!store.markSeen(envelope.msgId, now)) {
            stats = stats.copy(duplicates = stats.duplicates + 1)
            return
        }
        handleNew(envelope.hopped(label), from = linkId, now = now)
    }

    suspend fun onFromUplink(envelope: Envelope) {
        val now = clock()
        val problem = validate(envelope, now)
        if (problem != null) {
            output.log("drop ${envelope.shortId} from server: $problem")
            return
        }
        if (!store.markSeen(envelope.msgId, now)) return
        handleNew(envelope.stamped(label), from = UPLINK, now = now)
    }

    suspend fun setGateway(on: Boolean) {
        if (isGateway == on) return
        isGateway = on
        output.log(if (on) "became GATEWAY (server reachable)" else "gateway lost")
        if (on) {
            val now = clock()
            val ups = store.liveOfType(EnvelopeType.UP, now) +
                store.liveOfType(EnvelopeType.BROADCAST, now).filter { !store.hasReceipt(it.envelope.msgId) }
            if (ups.isNotEmpty()) {
                upload(ups.map { it.envelope }, ups.filter { it.local }.map { it.envelope.msgId }.toSet())
            }
        }
    }

    suspend fun housekeeping(maxEnvelopes: Int = MAX_STORE, seenTtlMs: Long = SEEN_TTL_MS) {
        store.housekeeping(clock(), maxEnvelopes, seenTtlMs)
    }

    // ---- routing ------------------------------------------------------------------------

    private suspend fun handleNew(env: Envelope, from: String, now: Long) {
        when (env.type) {
            EnvelopeType.RECEIPT -> {
                val pk = serverSignPk
                val c = crypto
                if (pk != null && c != null) {
                    val receipt = Receipts.parseAndVerify(env, pk, c)
                    if (receipt == null) {
                        stats = stats.copy(dropped = stats.dropped + 1)
                        output.log("drop ${env.shortId}: receipt signature invalid")
                        return
                    }
                    stats = stats.copy(receipts = stats.receipts + 1)
                    store.addReceipt(receipt.targetId, env.expiresAt)
                    val target = store.get(receipt.targetId)
                    val mine = target?.local == true
                    // A broadcast stays in the mesh after the server has it: people nearby still need to see it.
                    if (target?.envelope?.type != EnvelopeType.BROADCAST) {
                        store.remove(receipt.targetId)
                        ownSends.remove(receipt.targetId)
                    }
                    output.onReceipt(receipt, mine)
                }
                // Without server keys we can't verify, but still carry it for others.
                store.put(env, local = false, now = now)
                flood(env, except = from, local = false)
            }

            EnvelopeType.UP -> {
                if (store.hasReceipt(env.msgId)) return
                store.put(env, local = false, now = now)
                if (isGateway) upload(listOf(env), emptySet()) else flood(env, except = from, local = false)
            }

            EnvelopeType.DOWN -> {
                if (store.hasReceipt(env.msgId)) return
                if (env.dest == myAddress) {
                    stats = stats.copy(deliveredHere = stats.deliveredHere + 1)
                    output.log("DOWN ${env.shortId} is for me (${env.hops} hops, ${env.trace.joinToString(">")})")
                    output.deliverLocal(env)
                    return
                }
                store.put(env, local = false, now = now)
                flood(env, except = from, local = false)
            }

            EnvelopeType.BROADCAST -> {
                val c = crypto
                if (c == null || Broadcasts.open(env, c) == null) {
                    stats = stats.copy(dropped = stats.dropped + 1)
                    output.log("drop ${env.shortId}: broadcast signature invalid")
                    return
                }
                if (!supersede(env, now)) {
                    output.log("skip ${env.shortId}: we hold a newer broadcast from ${env.dest.take(6)}")
                    return
                }
                stats = stats.copy(broadcasts = stats.broadcasts + 1)
                store.put(env, local = false, now = now)
                output.log("BROADCAST ${env.shortId} from ${env.dest.take(6)} (${env.hops} hops, ${env.trace.joinToString(">")})")
                output.deliverBroadcast(env)
                flood(env, except = from, local = false)
                if (isGateway && from != UPLINK && !store.hasReceipt(env.msgId)) upload(listOf(env), emptySet())
            }
        }
    }

    /**
     * Keeps only the newest broadcast per sender (dest = sender address). Returns false, and
     * changes nothing, if we already hold a newer one than [env].
     */
    private suspend fun supersede(env: Envelope, now: Long): Boolean {
        val held = store.liveOfType(EnvelopeType.BROADCAST, now)
            .filter { it.envelope.dest == env.dest && it.envelope.msgId != env.msgId }
        if (held.any { Broadcasts.isNewer(it.envelope, env) }) return false
        held.forEach {
            store.remove(it.envelope.msgId)
            ownSends.remove(it.envelope.msgId)
        }
        return true
    }

    private fun flood(env: Envelope, except: String?, local: Boolean) {
        if (env.hops >= env.maxHops) {
            output.log("hold ${env.shortId}: hop limit ${env.maxHops} reached")
            return
        }
        links.filter { it != except }.forEach { send(it, env, local) }
    }

    private fun send(linkId: String, env: Envelope, local: Boolean) {
        output.sendToLink(linkId, env)
        stats = stats.copy(forwarded = stats.forwarded + 1)
        if (local) {
            val set = ownSends.getOrPut(env.msgId) { HashSet() }
            if (set.add(linkId)) output.onOwnSent(env.msgId, set.size, viaUplink = false)
        }
    }

    private fun upload(envelopes: List<Envelope>, localIds: Set<String>) {
        output.uplink(envelopes)
        stats = stats.copy(uploaded = stats.uploaded + envelopes.size)
        localIds.forEach { output.onOwnSent(it, 0, viaUplink = true) }
    }

    private fun validate(env: Envelope, now: Long): String? = when {
        env.isExpired(now) -> "expired"
        env.expiresAt - now > Envelope.MAX_LIFETIME_MS -> "expiry too far in future"
        env.maxHops > Envelope.MAX_HOPS_LIMIT -> "maxHops too large"
        else -> null
    }

    companion object {
        const val UPLINK = "uplink"
        const val SYNC_LIMIT = 300
        const val MAX_STORE = 1000
        const val SEEN_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
