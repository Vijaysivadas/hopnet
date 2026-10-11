package app.hopnet.core.mesh

import kotlinx.coroutines.flow.Flow

/** How a node describes itself to neighbours (Nearby endpoint name + HELLO frame). */
data class NodeInfo(val nodeId: String, val label: String, val gateway: Boolean)

sealed interface TransportEvent {
    data class LinkUp(val linkId: String, val remote: NodeInfo) : TransportEvent
    data class LinkDown(val linkId: String) : TransportEvent
    class Received(val linkId: String, val bytes: ByteArray) : TransportEvent
    data class Log(val message: String) : TransportEvent
}

/**
 * Phone-to-phone links. Google Nearby Connections on Android, in-memory wiring in tests.
 * A transport only moves opaque frames; all routing decisions live in [Router].
 */
interface MeshTransport {
    val events: Flow<TransportEvent>

    fun start(local: NodeInfo)

    /** Called when label or gateway status changes, so neighbours can see it before connecting. */
    fun updateLocal(local: NodeInfo)

    fun send(linkId: String, bytes: ByteArray)

    fun stop()
}
