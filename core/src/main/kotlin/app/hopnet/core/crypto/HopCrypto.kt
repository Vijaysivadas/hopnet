package app.hopnet.core.crypto

import app.hopnet.core.protocol.Ids

/**
 * The libsodium primitives Hopnet uses. Implemented with lazysodium-android in :sdk and
 * lazysodium-java in JVM tests; the server uses PyNaCl. Never add home-made constructions here.
 */
interface HopCrypto {
    /** Ed25519: publicKey 32 bytes, secretKey 64 bytes. */
    fun newSignKeyPair(): KeyPair

    /** X25519: publicKey 32 bytes, secretKey 32 bytes. */
    fun newBoxKeyPair(): KeyPair

    /** Ed25519 detached signature (64 bytes). */
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    /** crypto_box_seal: anonymous sender, only [recipientPk]'s owner can open. Adds 48 bytes. */
    fun seal(message: ByteArray, recipientPk: ByteArray): ByteArray

    fun openSealed(cipher: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray?
}

class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

/** A user's long-term keys. The address is what other people's messages are delivered to. */
class Identity(val sign: KeyPair, val box: KeyPair) {
    val address: String = Ids.addressOf(sign.publicKey)

    companion object {
        fun generate(crypto: HopCrypto) = Identity(crypto.newSignKeyPair(), crypto.newBoxKeyPair())
    }
}

/** Server public keys, learned at registration and used to seal UP payloads and verify DOWN/receipts. */
class ServerKeys(val signPk: ByteArray, val encPk: ByteArray)
