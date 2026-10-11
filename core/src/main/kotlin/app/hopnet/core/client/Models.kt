package app.hopnet.core.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val HopnetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Signed by the sender, sealed to the server. `id`/`exp` must match the envelope header. */
@Serializable
data class UpInner(
    val v: Int = 1,
    val id: String,
    val from: String,
    val ts: Long,
    val exp: Long,
    val svc: String,
    val kind: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** Signed by the server, sealed to the recipient. */
@Serializable
data class DownInner(
    val v: Int = 1,
    val id: String,
    val to: String,
    val ts: Long,
    val exp: Long,
    val svc: String,
    val kind: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** A verified, decrypted message from the server for this phone's user. */
data class IncomingMessage(
    val msgId: String,
    val service: String,
    val kind: String,
    val data: JsonObject,
    val sentAt: Long,
    val hops: Int,
    val path: List<String>,
)

/** A verified broadcast from another phone. [from] is the sender's address; [path] the phones it hopped through. */
data class IncomingBroadcast(
    val msgId: String,
    val from: String,
    val service: String,
    val kind: String,
    val data: JsonObject,
    val sentAt: Long,
    val expiresAt: Long,
    val hops: Int,
    val path: List<String>,
)

/** Progress of something this phone sent. */
sealed interface OutgoingUpdate {
    val msgId: String

    /** Handed to [peers] neighbouring phones, or uploaded straight to the server. */
    data class Forwarded(override val msgId: String, val peers: Int, val viaUplink: Boolean) : OutgoingUpdate

    /** Server receipt arrived: it got there in [hops] hops along [path]. */
    data class AtServer(override val msgId: String, val hops: Int, val path: List<String>) : OutgoingUpdate

    data class Rejected(override val msgId: String) : OutgoingUpdate
}

@Serializable
data class ServerInfo(val name: String = "hopnet", val signPk: String, val encPk: String)

@Serializable
data class RegisterRequest(val handle: String, val name: String, val signPk: String, val encPk: String, val sig: String)

@Serializable
data class RegisterResponse(val address: String, val handle: String, val name: String, val server: ServerInfo)

@Serializable
data class UserInfo(val handle: String, val name: String)
