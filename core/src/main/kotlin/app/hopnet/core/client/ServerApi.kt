package app.hopnet.core.client

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.crypto.Identity
import app.hopnet.core.gateway.GatewayClient
import app.hopnet.core.protocol.B64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class HopnetApiException(message: String) : IOException(message)

/** The few plain HTTP calls a phone makes while online (registration and handle lookup). */
class ServerApi(private val http: OkHttpClient = GatewayClient.defaultHttpClient()) {

    suspend fun info(baseUrl: String): ServerInfo =
        HopnetJson.decodeFromString(ServerInfo.serializer(), get(baseUrl, "/v1/info") ?: throw HopnetApiException("server not found"))

    /** Claims [handle] for this identity. Signing proves we hold the key being registered. */
    suspend fun register(baseUrl: String, handle: String, name: String, identity: Identity, crypto: HopCrypto): RegisterResponse {
        val signPk = B64.encode(identity.sign.publicKey)
        val encPk = B64.encode(identity.box.publicKey)
        val proof = "HNREG1|$handle|$signPk|$encPk".toByteArray()
        val req = RegisterRequest(handle, name, signPk, encPk, B64.encode(crypto.sign(proof, identity.sign.secretKey)))
        val body = post(baseUrl, "/v1/register", HopnetJson.encodeToString(RegisterRequest.serializer(), req))
        return HopnetJson.decodeFromString(RegisterResponse.serializer(), body)
    }

    suspend fun lookup(baseUrl: String, handle: String): UserInfo? =
        get(baseUrl, "/v1/users/${handle.trim().lowercase()}")?.let { HopnetJson.decodeFromString(UserInfo.serializer(), it) }

    private suspend fun get(baseUrl: String, path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).get().build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw HopnetApiException(errorText(resp.code, text))
                else -> text
            }
        }
    }

    private suspend fun post(baseUrl: String, path: String, json: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw HopnetApiException(errorText(resp.code, text))
            text
        }
    }

    private fun errorText(code: Int, body: String): String {
        val detail = runCatching { HopnetJson.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
        return detail ?: "server error $code"
    }
}
