package app.hopnet.core.protocol

import app.hopnet.core.crypto.HopCrypto
import java.nio.ByteBuffer

enum class ReceiptStatus(val code: Int) {
    /** An UP envelope reached the server. */
    RECEIVED(1),
    /** A DOWN envelope was acknowledged by its recipient. */
    DELIVERED(2),
    /** The server refused an UP envelope (bad signature, unknown sender, unknown service). */
    REJECTED(3);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

class Receipt(val targetId: String, val status: ReceiptStatus, val hops: Int, val path: List<String>)

/**
 * RECEIPT body: target(16) | status(1) | hops(1) | traceLen(1) | trace "A>B>G" | sig(64)
 * sig = Ed25519(server, "HNR1" | everything before sig).
 * The receipt's own msgId is derived from (target, status) so re-issued receipts de-duplicate.
 */
object Receipts {
    private val DOMAIN = "HNR1".toByteArray(Charsets.US_ASCII)
    const val TTL_MS = 24L * 60 * 60 * 1000

    fun receiptMsgId(targetId: String, status: ReceiptStatus): String =
        Hex.encode(Ids.sha256(DOMAIN, Hex.decode(targetId), byteArrayOf(status.code.toByte())).copyOf(Ids.ID_BYTES))

    /** Builds a receipt envelope. The real server does this in Python; used here by tests. */
    fun build(
        targetId: String,
        status: ReceiptStatus,
        hops: Int,
        path: List<String>,
        serverSignSk: ByteArray,
        crypto: HopCrypto,
        now: Long,
    ): Envelope {
        var trace = path.joinToString(">").toByteArray(Charsets.UTF_8)
        if (trace.size > 255) trace = trace.copyOf(255)
        val unsigned = ByteBuffer.allocate(16 + 3 + trace.size)
            .put(Hex.decode(targetId))
            .put(status.code.toByte())
            .put(hops.coerceIn(0, 255).toByte())
            .put(trace.size.toByte())
            .put(trace)
            .array()
        val signature = crypto.sign(DOMAIN + unsigned, serverSignSk)
        return Envelope(
            type = EnvelopeType.RECEIPT,
            flags = 0,
            msgId = receiptMsgId(targetId, status),
            dest = Ids.ZERO,
            hops = 0,
            maxHops = Envelope.DEFAULT_MAX_HOPS,
            createdAt = now,
            expiresAt = now + TTL_MS,
            body = unsigned + signature,
        )
    }

    fun parseAndVerify(env: Envelope, serverSignPk: ByteArray, crypto: HopCrypto): Receipt? {
        if (env.type != EnvelopeType.RECEIPT || env.body.size < 16 + 3 + 64) return null
        val unsigned = env.body.copyOfRange(0, env.body.size - 64)
        val signature = env.body.copyOfRange(env.body.size - 64, env.body.size)
        if (!crypto.verify(signature, DOMAIN + unsigned, serverSignPk)) return null
        val buf = ByteBuffer.wrap(unsigned)
        val target = Hex.encode(ByteArray(16).also { buf.get(it) })
        val status = ReceiptStatus.of(buf.get().toInt() and 0xff) ?: return null
        val hops = buf.get().toInt() and 0xff
        val traceLen = buf.get().toInt() and 0xff
        if (traceLen != buf.remaining()) return null
        val trace = String(ByteArray(traceLen).also { buf.get(it) }, Charsets.UTF_8)
        if (env.msgId != receiptMsgId(target, status)) return null
        return Receipt(target, status, hops, if (trace.isEmpty()) emptyList() else trace.split(">"))
    }
}
