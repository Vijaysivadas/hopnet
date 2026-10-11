package app.hopnet.core.store

import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StoredEnvelope(val envelope: Envelope, val local: Boolean)

/**
 * Store-and-forward state of one node: envelopes it carries, ids it has seen (duplicate
 * filter) and targets the server has receipted (so late copies are dropped).
 * Room-backed on Android, in-memory for JVM tests.
 */
interface EnvelopeStore {
    /** Records [msgId] as seen; returns true only the first time. */
    suspend fun markSeen(msgId: String, now: Long): Boolean

    suspend fun put(envelope: Envelope, local: Boolean, now: Long)

    suspend fun remove(msgId: String)

    suspend fun isLocal(msgId: String): Boolean

    suspend fun get(msgId: String): StoredEnvelope?

    /** Unexpired envelopes: broadcasts, then receipts, then own messages, then the rest; newest first. */
    suspend fun live(now: Long, limit: Int): List<StoredEnvelope>

    suspend fun liveOfType(type: EnvelopeType, now: Long): List<StoredEnvelope>

    suspend fun addReceipt(targetId: String, expiresAt: Long)

    suspend fun hasReceipt(targetId: String): Boolean

    suspend fun count(): Int

    /**
     * Drops expired envelopes/receipts, old seen ids and the oldest relayed envelopes over [maxEnvelopes].
     * Broadcasts are never evicted early: there is at most one per sender and they may be someone's call for help.
     */
    suspend fun housekeeping(now: Long, maxEnvelopes: Int, seenTtlMs: Long)

    suspend fun clear()
}

internal fun liveOrder(a: StoredEnvelope, b: StoredEnvelope): Int {
    fun rank(s: StoredEnvelope) = when {
        s.envelope.type == EnvelopeType.BROADCAST -> 0
        s.envelope.type == EnvelopeType.RECEIPT -> 1
        s.local -> 2
        else -> 3
    }
    val r = rank(a).compareTo(rank(b))
    return if (r != 0) r else b.envelope.createdAt.compareTo(a.envelope.createdAt)
}

class InMemoryEnvelopeStore : EnvelopeStore {
    private val mutex = Mutex()
    private val envelopes = LinkedHashMap<String, Pair<StoredEnvelope, Long>>()
    private val seen = HashMap<String, Long>()
    private val receipts = HashMap<String, Long>()

    override suspend fun markSeen(msgId: String, now: Long) = mutex.withLock { seen.putIfAbsent(msgId, now) == null }

    override suspend fun put(envelope: Envelope, local: Boolean, now: Long) = mutex.withLock {
        val existing = envelopes[envelope.msgId]
        envelopes[envelope.msgId] = StoredEnvelope(envelope, local || existing?.first?.local == true) to now
    }

    override suspend fun remove(msgId: String) = mutex.withLock { envelopes.remove(msgId); Unit }

    override suspend fun isLocal(msgId: String) = mutex.withLock { envelopes[msgId]?.first?.local == true }

    override suspend fun get(msgId: String) = mutex.withLock { envelopes[msgId]?.first }

    override suspend fun live(now: Long, limit: Int) = mutex.withLock {
        envelopes.values.map { it.first }.filter { !it.envelope.isExpired(now) }.sortedWith(::liveOrder).take(limit)
    }

    override suspend fun liveOfType(type: EnvelopeType, now: Long) = mutex.withLock {
        envelopes.values.map { it.first }.filter { it.envelope.type == type && !it.envelope.isExpired(now) }
    }

    override suspend fun addReceipt(targetId: String, expiresAt: Long) = mutex.withLock { receipts[targetId] = expiresAt }

    override suspend fun hasReceipt(targetId: String) = mutex.withLock { targetId in receipts }

    override suspend fun count() = mutex.withLock { envelopes.size }

    override suspend fun housekeeping(now: Long, maxEnvelopes: Int, seenTtlMs: Long) = mutex.withLock {
        envelopes.entries.removeAll { it.value.first.envelope.isExpired(now) }
        receipts.entries.removeAll { it.value <= now }
        seen.entries.removeAll { now - it.value > seenTtlMs }
        val overflow = envelopes.size - maxEnvelopes
        if (overflow > 0) {
            envelopes.entries.filter { !it.value.first.local && it.value.first.envelope.type != EnvelopeType.BROADCAST }
                .sortedBy { it.value.second }
                .take(overflow)
                .forEach { envelopes.remove(it.key) }
        }
    }

    override suspend fun clear() = mutex.withLock {
        envelopes.clear(); seen.clear(); receipts.clear()
    }
}
