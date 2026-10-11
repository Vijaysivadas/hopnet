package app.hopnet.core.protocol

import java.nio.ByteBuffer

enum class EnvelopeType(val code: Int) {
    /** Phone → server. Body is sealed to the server and signed by the sender. */
    UP(1),
    /** Server → one address. Body is sealed to the recipient and signed by the server. */
    DOWN(2),
    /** Server-signed "this msgId is done" notice that purges stored copies across the mesh. */
    RECEIPT(3),
    /**
     * Phone → every phone (and the server, via any gateway). Signed by the sender but not
     * encrypted, so every phone can read it. `dest` is the sender's address: each phone keeps
     * only the sender's newest broadcast. Used by Find Me.
     */
    BROADCAST(4);

    companion object {
        fun of(code: Int): EnvelopeType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The only thing relays ever see. Everything except [hops] and [trace] is fixed by the origin;
 * msgId/expiresAt are also bound inside the signed body so tampering is detected end-to-end.
 */
class Envelope(
    val type: EnvelopeType,
    val flags: Int,
    val msgId: String,
    val dest: String,
    val hops: Int,
    val maxHops: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val body: ByteArray,
    val trace: List<String> = emptyList(),
) {
    val traceEnabled: Boolean get() = flags and FLAG_TRACE != 0
    val shortId: String get() = msgId.take(6)

    fun isExpired(now: Long) = now >= expiresAt

    /** Copy as received over one more link: hop count +1 and our label on the trace. */
    fun hopped(label: String) = copy(hops = (hops + 1).coerceAtMost(255), trace = traced(label))

    fun stamped(label: String) = copy(trace = traced(label))

    private fun traced(label: String) =
        if (traceEnabled && trace.size < MAX_TRACE) trace + Labels.clean(label) else trace

    private fun copy(hops: Int = this.hops, trace: List<String> = this.trace) =
        Envelope(type, flags, msgId, dest, hops, maxHops, createdAt, expiresAt, body, trace)

    override fun toString() = "Envelope($type $shortId hops=$hops/$maxHops dest=${dest.take(6)} trace=$trace)"

    companion object {
        const val FLAG_TRACE = 0x01
        const val MAX_TRACE = 16
        const val MAX_BODY = 4096
        const val MAX_HOPS_LIMIT = 32
        const val DEFAULT_MAX_HOPS = 12
        const val MAX_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    }
}

class ProtocolException(message: String) : Exception(message)

/**
 * Binary layout (big-endian), mirrored by server/hopnet_server/envelope.py:
 * magic(1)=0x48 | version(1) | type(1) | flags(1) | msgId(16) | dest(16) | hops(1) | maxHops(1)
 * | createdAt(8) | expiresAt(8) | bodyLen(2) | body | traceCount(1) | (len(1) + utf8)*
 */
object EnvelopeCodec {
    const val MAGIC = 0x48
    const val VERSION = 1
    const val HEADER_SIZE = 56

    fun encode(e: Envelope): ByteArray {
        require(e.body.size <= Envelope.MAX_BODY) { "body too large" }
        val trace = e.trace.take(Envelope.MAX_TRACE).map { Labels.clean(it).toByteArray(Charsets.UTF_8) }
        val size = HEADER_SIZE + e.body.size + 1 + trace.sumOf { 1 + it.size }
        val buf = ByteBuffer.allocate(size)
        buf.put(MAGIC.toByte())
        buf.put(VERSION.toByte())
        buf.put(e.type.code.toByte())
        buf.put(e.flags.toByte())
        buf.put(Hex.decode(e.msgId))
        buf.put(Hex.decode(e.dest))
        buf.put(e.hops.toByte())
        buf.put(e.maxHops.toByte())
        buf.putLong(e.createdAt)
        buf.putLong(e.expiresAt)
        buf.putShort(e.body.size.toShort())
        buf.put(e.body)
        buf.put(trace.size.toByte())
        trace.forEach { buf.put(it.size.toByte()); buf.put(it) }
        return buf.array()
    }

    fun decode(bytes: ByteArray): Envelope {
        if (bytes.size < HEADER_SIZE + 1) throw ProtocolException("too short")
        val buf = ByteBuffer.wrap(bytes)
        if (buf.get().toInt() and 0xff != MAGIC) throw ProtocolException("bad magic")
        val version = buf.get().toInt() and 0xff
        if (version != VERSION) throw ProtocolException("unsupported version $version")
        val type = EnvelopeType.of(buf.get().toInt() and 0xff) ?: throw ProtocolException("bad type")
        val flags = buf.get().toInt() and 0xff
        val msgId = ByteArray(16).also { buf.get(it) }
        val dest = ByteArray(16).also { buf.get(it) }
        val hops = buf.get().toInt() and 0xff
        val maxHops = buf.get().toInt() and 0xff
        val createdAt = buf.getLong()
        val expiresAt = buf.getLong()
        val bodyLen = buf.getShort().toInt() and 0xffff
        if (bodyLen > Envelope.MAX_BODY || buf.remaining() < bodyLen + 1) throw ProtocolException("bad body length")
        val body = ByteArray(bodyLen).also { buf.get(it) }
        val traceCount = buf.get().toInt() and 0xff
        if (traceCount > Envelope.MAX_TRACE) throw ProtocolException("trace too long")
        val trace = ArrayList<String>(traceCount)
        repeat(traceCount) {
            if (!buf.hasRemaining()) throw ProtocolException("truncated trace")
            val len = buf.get().toInt() and 0xff
            if (len > Labels.MAX_BYTES || buf.remaining() < len) throw ProtocolException("bad trace entry")
            trace += String(ByteArray(len).also { buf.get(it) }, Charsets.UTF_8)
        }
        return Envelope(type, flags, Hex.encode(msgId), Hex.encode(dest), hops, maxHops, createdAt, expiresAt, body, trace)
    }

    fun decodeOrNull(bytes: ByteArray): Envelope? = try {
        decode(bytes)
    } catch (_: ProtocolException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
