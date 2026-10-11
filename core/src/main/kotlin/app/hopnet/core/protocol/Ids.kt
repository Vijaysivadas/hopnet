package app.hopnet.core.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    fun isHex(s: String, bytes: Int): Boolean =
        s.length == bytes * 2 && s.all { it in '0'..'9' || it in 'a'..'f' }
}

object B64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}

object Ids {
    const val ID_BYTES = 16
    /** Destination used by envelopes addressed to the server (UP) or to everyone (RECEIPT). */
    val ZERO: String = "0".repeat(ID_BYTES * 2)

    private val random = SecureRandom()

    fun newMsgId(): String = Hex.encode(ByteArray(ID_BYTES).also(random::nextBytes))

    fun newNodeId(): String = Hex.encode(ByteArray(6).also(random::nextBytes))

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    /** A user's mailbox address: first 16 bytes of SHA-256(signing public key). */
    fun addressOf(signPk: ByteArray): String = Hex.encode(sha256(signPk).copyOf(ID_BYTES))
}

/** Node labels travel in endpoint names and hop traces, so keep them short and delimiter-free. */
object Labels {
    const val MAX_BYTES = 24

    fun clean(raw: String): String {
        var s = raw.trim().replace(Regex("[|>\\s]+"), "-").ifEmpty { "node" }
        while (s.toByteArray(Charsets.UTF_8).size > MAX_BYTES) s = s.dropLast(1)
        return s
    }
}
