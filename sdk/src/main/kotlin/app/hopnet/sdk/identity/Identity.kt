package app.hopnet.sdk.identity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.crypto.Identity
import app.hopnet.core.crypto.KeyPair
import app.hopnet.core.protocol.Ids
import app.hopnet.core.protocol.Labels
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Who this phone's user is on Hopnet, after a successful online registration. */
data class HopnetProfile(
    val serverUrl: String,
    val handle: String,
    val name: String,
    val address: String,
    val serverSignPk: String,
    val serverEncPk: String,
)

/** The user's private keys, encrypted at rest with an AES key that never leaves Android Keystore. */
internal class IdentityVault(context: Context, private val crypto: HopCrypto) {
    private val file = File(context.filesDir, "hopnet_identity.bin")

    fun loadOrCreate(): Identity = runCatching { load() }.getOrNull() ?: create()

    private fun load(): Identity? {
        if (!file.exists()) return null
        val plain = decrypt(file.readBytes())
        if (plain.size != 32 + 64 + 32 + 32) return null
        return Identity(
            KeyPair(plain.copyOfRange(0, 32), plain.copyOfRange(32, 96)),
            KeyPair(plain.copyOfRange(96, 128), plain.copyOfRange(128, 160)),
        )
    }

    private fun create(): Identity {
        val id = Identity.generate(crypto)
        val plain = id.sign.publicKey + id.sign.secretKey + id.box.publicKey + id.box.secretKey
        file.writeBytes(encrypt(plain))
        return id
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, ivLen))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    private companion object {
        const val ALIAS = "app.hopnet.identity.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Non-secret settings: node id, label, demo allowlist and the registered profile. */
internal class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("hopnet_sdk", Context.MODE_PRIVATE)

    val nodeId: String = prefs.getString(KEY_NODE, null) ?: Ids.newNodeId().also { prefs.edit().putString(KEY_NODE, it).apply() }

    var label: String
        get() = prefs.getString(KEY_LABEL, null) ?: Labels.clean(Build.MODEL ?: "phone")
        set(value) = prefs.edit().putString(KEY_LABEL, Labels.clean(value)).apply()

    var allowlist: Set<String>
        get() = prefs.getStringSet(KEY_ALLOW, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_ALLOW, value).apply()

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    var profile: HopnetProfile?
        get() {
            val handle = prefs.getString("p_handle", null) ?: return null
            return HopnetProfile(
                serverUrl = prefs.getString("p_server", null) ?: return null,
                handle = handle,
                name = prefs.getString("p_name", handle) ?: handle,
                address = prefs.getString("p_address", null) ?: return null,
                serverSignPk = prefs.getString("p_sign", null) ?: return null,
                serverEncPk = prefs.getString("p_enc", null) ?: return null,
            )
        }
        set(p) {
            prefs.edit().apply {
                if (p == null) {
                    listOf("p_handle", "p_server", "p_name", "p_address", "p_sign", "p_enc").forEach(::remove)
                } else {
                    putString("p_handle", p.handle); putString("p_server", p.serverUrl); putString("p_name", p.name)
                    putString("p_address", p.address); putString("p_sign", p.serverSignPk); putString("p_enc", p.serverEncPk)
                }
            }.apply()
        }

    private companion object {
        const val KEY_NODE = "node_id"
        const val KEY_LABEL = "label"
        const val KEY_ALLOW = "allowlist"
        const val KEY_SERVER = "server_url"
    }
}
