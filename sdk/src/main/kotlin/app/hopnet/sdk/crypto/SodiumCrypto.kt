package app.hopnet.sdk.crypto

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.crypto.KeyPair
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/** libsodium on Android (lazysodium + JNA). Same primitives the server uses through PyNaCl. */
internal class SodiumCrypto : HopCrypto {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    override fun newSignKeyPair(): KeyPair {
        val pk = ByteArray(32)
        val sk = ByteArray(64)
        check(ls.cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair failed" }
        return KeyPair(pk, sk)
    }

    override fun newBoxKeyPair(): KeyPair {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        check(ls.cryptoBoxKeypair(pk, sk)) { "crypto_box_keypair failed" }
        return KeyPair(pk, sk)
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val sig = ByteArray(64)
        check(ls.cryptoSignDetached(sig, message, message.size.toLong(), secretKey)) { "crypto_sign_detached failed" }
        return sig
    }

    override fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        signature.size == 64 && publicKey.size == 32 &&
            ls.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    override fun seal(message: ByteArray, recipientPk: ByteArray): ByteArray {
        val out = ByteArray(message.size + 48)
        check(ls.cryptoBoxSeal(out, message, message.size.toLong(), recipientPk)) { "crypto_box_seal failed" }
        return out
    }

    override fun openSealed(cipher: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray? {
        if (cipher.size < 48) return null
        val out = ByteArray(cipher.size - 48)
        return if (ls.cryptoBoxSealOpen(out, cipher, cipher.size.toLong(), publicKey, secretKey)) out else null
    }
}
