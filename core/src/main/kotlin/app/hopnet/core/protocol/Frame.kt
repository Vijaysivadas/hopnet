package app.hopnet.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What travels over a single phone-to-phone link (one Nearby BYTES payload per frame). */
sealed interface Frame {
    data class Hello(val nodeId: String, val label: String, val gateway: Boolean) : Frame
    class Env(val envelope: Envelope) : Frame
}

object FrameCodec {
    private const val HELLO = 0x01
    private const val ENV = 0x02
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class HelloJson(val node: String, val label: String, val gw: Boolean = false)

    fun encode(frame: Frame): ByteArray = when (frame) {
        is Frame.Hello -> byteArrayOf(HELLO.toByte()) +
            json.encodeToString(HelloJson.serializer(), HelloJson(frame.nodeId, frame.label, frame.gateway)).toByteArray()
        is Frame.Env -> byteArrayOf(ENV.toByte()) + EnvelopeCodec.encode(frame.envelope)
    }

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.isEmpty()) return null
        val payload = bytes.copyOfRange(1, bytes.size)
        return when (bytes[0].toInt()) {
            HELLO -> runCatching {
                val h = json.decodeFromString(HelloJson.serializer(), String(payload, Charsets.UTF_8))
                Frame.Hello(h.node, Labels.clean(h.label), h.gw)
            }.getOrNull()
            ENV -> EnvelopeCodec.decodeOrNull(payload)?.let { Frame.Env(it) }
            else -> null
        }
    }
}
