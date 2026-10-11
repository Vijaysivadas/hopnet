package app.hopnet.core.protocol

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.crypto.KeyPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer

/** Signed by the sender, readable by everyone. `id`/`from`/`ts`/`exp` must match the envelope header. */
@Serializable
data class BroadcastInner(
    val v: Int = 1,
    val id: String,
    val from: String,
    val ts: Long,
    val exp: Long,
    val svc: String,
    val kind: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/**
 * Body of BROADCAST envelopes, mirrored by server/hopnet_server/crypto.py:
 *   body      = signPk(32) | signature(64) | jsonLen(u16) | json
 *   signature = Ed25519(sender, "HNB1" | json)
 * Any phone can check it offline: the envelope's dest must equal SHA-256(signPk)[0..16], the
 * sender's address. So nobody can forge, alter or replace someone else's broadcast.
 */
object Broadcasts {
    private val DOMAIN = "HNB1".toByteArray(Charsets.US_ASCII)
    private const val SIGN_PK_BYTES = 32
    private const val HEADER = SIGN_PK_BYTES + SealedPayload.SIGNATURE_BYTES + 2
    const val MAX_JSON = Envelope.MAX_BODY - HEADER
    const val DEFAULT_MAX_HOPS = 16

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun build(inner: BroadcastInner, signer: KeyPair, crypto: HopCrypto, maxHops: Int = DEFAULT_MAX_HOPS): Envelope {
        require(Ids.addressOf(signer.publicKey) == inner.from) { "broadcast must come from the signer's address" }
        val js = json.encodeToString(BroadcastInner.serializer(), inner).toByteArray(Charsets.UTF_8)
        require(js.size <= MAX_JSON) { "broadcast too large (${js.size} > $MAX_JSON bytes)" }
        val signature = crypto.sign(DOMAIN + js, signer.secretKey)
        val body = ByteBuffer.allocate(HEADER + js.size)
            .put(signer.publicKey)
            .put(signature)
            .putShort(js.size.toShort())
            .put(js)
            .array()
        return Envelope(
            type = EnvelopeType.BROADCAST,
            flags = Envelope.FLAG_TRACE,
            msgId = inner.id,
            dest = inner.from,
            hops = 0,
            maxHops = maxHops,
            createdAt = inner.ts,
            expiresAt = inner.exp,
            body = body,
        )
    }

    /** Verifies signature, signer address and header binding; returns the content or null. */
    fun open(env: Envelope, crypto: HopCrypto): BroadcastInner? {
        if (env.type != EnvelopeType.BROADCAST || env.body.size < HEADER) return null
        val buf = ByteBuffer.wrap(env.body)
        val signPk = ByteArray(SIGN_PK_BYTES).also { buf.get(it) }
        val signature = ByteArray(SealedPayload.SIGNATURE_BYTES).also { buf.get(it) }
        val len = buf.getShort().toInt() and 0xffff
        if (len != buf.remaining()) return null
        val js = ByteArray(len).also { buf.get(it) }
        if (Ids.addressOf(signPk) != env.dest) return null
        if (!crypto.verify(signature, DOMAIN + js, signPk)) return null
        val inner = runCatching { json.decodeFromString(BroadcastInner.serializer(), js.decodeToString()) }.getOrNull() ?: return null
        if (inner.id != env.msgId || inner.from != env.dest || inner.ts != env.createdAt || inner.exp != env.expiresAt) return null
        return inner
    }

    /** Whether [a] is a later broadcast than [b] from the same sender (ties broken by msgId). */
    fun isNewer(a: Envelope, b: Envelope): Boolean =
        a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.msgId > b.msgId)
}
