package app.hopnet.core.protocol

import app.hopnet.core.crypto.HopCrypto
import java.nio.ByteBuffer

/**
 * Body of UP and DOWN envelopes:
 *   plaintext = signature(64) | jsonLen(u16) | json | zero padding to a multiple of 256
 *   body      = crypto_box_seal(plaintext, recipientEncPk)
 * The signature covers the JSON bytes exactly as sent (no canonicalisation needed).
 * Padding hides the message length from relays.
 */
object SealedPayload {
    const val SIGNATURE_BYTES = 64
    const val PAD_TO = 256
    const val SEAL_OVERHEAD = 48
    const val MAX_JSON = Envelope.MAX_BODY - SEAL_OVERHEAD - SIGNATURE_BYTES - 2 - PAD_TO

    fun seal(json: ByteArray, signerSk: ByteArray, recipientEncPk: ByteArray, crypto: HopCrypto): ByteArray {
        require(json.size <= MAX_JSON) { "payload too large (${json.size} > $MAX_JSON bytes)" }
        val signature = crypto.sign(json, signerSk)
        val raw = SIGNATURE_BYTES + 2 + json.size
        val padded = (raw + PAD_TO - 1) / PAD_TO * PAD_TO
        val buf = ByteBuffer.allocate(padded)
        buf.put(signature)
        buf.putShort(json.size.toShort())
        buf.put(json)
        return crypto.seal(buf.array(), recipientEncPk)
    }

    /** Opens the box and checks the signer; returns the JSON bytes or null if anything is off. */
    fun openAndVerify(
        body: ByteArray,
        encPk: ByteArray,
        encSk: ByteArray,
        signerPk: ByteArray,
        crypto: HopCrypto,
    ): ByteArray? {
        val plain = crypto.openSealed(body, encPk, encSk) ?: return null
        if (plain.size < SIGNATURE_BYTES + 2) return null
        val buf = ByteBuffer.wrap(plain)
        val signature = ByteArray(SIGNATURE_BYTES).also { buf.get(it) }
        val len = buf.getShort().toInt() and 0xffff
        if (len > buf.remaining()) return null
        val json = ByteArray(len).also { buf.get(it) }
        return if (crypto.verify(signature, json, signerPk)) json else null
    }
}
