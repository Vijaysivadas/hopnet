package app.hopnet.sdk.nearby

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.hopnet.core.mesh.MeshTransport
import app.hopnet.core.mesh.NodeInfo
import app.hopnet.core.mesh.TransportEvent
import app.hopnet.core.protocol.Labels
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Phone-to-phone links over Google Nearby Connections (P2P_CLUSTER: many-to-many, Bluetooth/BLE).
 * Every phone advertises and discovers at the same time and keeps up to [maxLinks] links.
 *
 * Endpoint name "HN1|<nodeId>|<label>|<gateway 0/1>" lets phones see who is who before connecting.
 * Collision rule: only the side with the smaller nodeId dials; the other waits [YIELD_MS] first.
 * All state lives on the main thread, where Nearby delivers its callbacks.
 */
internal class NearbyTransport(context: Context, private val serviceId: String) : MeshTransport {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = channel.receiveAsFlow()

    /** Lower-case labels this phone may link with; empty means anyone (demo topology control). */
    @Volatile
    var allowlist: Set<String> = emptySet()
        set(value) {
            field = value.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            main.post { enforceAllowlist() }
        }

    private val maxLinks = 4
    private var local: NodeInfo? = null
    private var running = false
    private val discovered = HashMap<String, NodeInfo>()
    private val connected = HashMap<String, NodeInfo>()
    private val pending = HashMap<String, Long>()
    private val incomingInfo = HashMap<String, NodeInfo>()
    private val failures = HashMap<String, Pair<Int, Long>>()
    private var lastDiscoveryRestart = 0L

    override fun start(local: NodeInfo) = post {
        this.local = local
        if (running) return@post
        running = true
        startAdvertising()
        startDiscovery()
        main.postDelayed(maintenance, MAINTENANCE_MS)
    }

    override fun updateLocal(local: NodeInfo) = post {
        val changed = this.local != local
        this.local = local
        if (running && changed) {
            client.stopAdvertising()
            startAdvertising()
        }
    }

    override fun send(linkId: String, bytes: ByteArray) = post {
        if (linkId !in connected) return@post
        if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            log("frame too large for ${label(linkId)}: ${bytes.size} B")
            return@post
        }
        client.sendPayload(linkId, Payload.fromBytes(bytes))
            .addOnFailureListener { log("send to ${label(linkId)} failed: ${describe(it)}") }
    }

    override fun stop() = post {
        if (!running) return@post
        running = false
        main.removeCallbacks(maintenance)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.keys.forEach { channel.trySend(TransportEvent.LinkDown(it)) }
        connected.clear(); pending.clear(); discovered.clear(); incomingInfo.clear(); failures.clear()
    }

    // ---- advertising & discovery ----------------------------------------------------------

    private fun startAdvertising() {
        val me = local ?: return
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(nameOf(me), serviceId, lifecycle, options)
            .addOnSuccessListener { log("advertising as ${me.label}${if (me.gateway) " [gateway]" else ""}") }
            .addOnFailureListener { if (statusOf(it) != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) log("advertising failed: ${describe(it)}") }
    }

    private fun startDiscovery() {
        lastDiscoveryRestart = SystemClock.elapsedRealtime()
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, discovery, options)
            .addOnSuccessListener { log("scanning for nearby phones") }
            .addOnFailureListener { if (statusOf(it) != ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) log("discovery failed: ${describe(it)}") }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != serviceId) return
            val remote = parseName(info.endpointName) ?: return
            val isNew = discovered.put(endpointId, remote) == null
            if (!allowed(remote)) return
            if (isNew) log("found ${remote.label}${if (remote.gateway) " [gateway]" else ""}")
            maybeConnect(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
        }
    }

    // ---- connections -------------------------------------------------------------------------

    private fun maybeConnect(endpointId: String, force: Boolean = false) {
        val me = local ?: return
        val remote = discovered[endpointId] ?: return
        if (!running || endpointId in connected || endpointId in pending || !allowed(remote)) return
        if (connected.size + pending.size >= maxLinks) return
        val now = SystemClock.elapsedRealtime()
        failures[endpointId]?.let { (_, retryAt) -> if (now < retryAt) return }
        if (!force && me.nodeId > remote.nodeId) {
            main.postDelayed({ maybeConnect(endpointId, force = true) }, YIELD_MS)
            return
        }
        pending[endpointId] = now
        client.requestConnection(nameOf(me), endpointId, lifecycle)
            .addOnFailureListener { e ->
                pending.remove(endpointId)
                val code = statusOf(e)
                if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) fail(endpointId, describe(e))
            }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remote = parseName(info.endpointName)
            val full = info.isIncomingConnection && connected.size >= maxLinks
            if (remote == null || !allowed(remote) || full || !running) {
                client.rejectConnection(endpointId)
                return
            }
            incomingInfo[endpointId] = remote
            pending[endpointId] = SystemClock.elapsedRealtime()
            // No PIN check: links are untrusted by design, every envelope is sealed and signed.
            client.acceptConnection(endpointId, payloads)
                .addOnFailureListener { log("accept ${remote.label} failed: ${describe(it)}") }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pending.remove(endpointId)
            val remote = incomingInfo.remove(endpointId) ?: discovered[endpointId]
            if (resolution.status.isSuccess && remote != null && running) {
                connected[endpointId] = remote
                failures.remove(endpointId)
                channel.trySend(TransportEvent.LinkUp(endpointId, remote))
            } else {
                if (resolution.status.isSuccess) client.disconnectFromEndpoint(endpointId)
                fail(endpointId, ConnectionsStatusCodes.getStatusCodeString(resolution.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connected.remove(endpointId) != null) channel.trySend(TransportEvent.LinkDown(endpointId))
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { channel.trySend(TransportEvent.Received(endpointId, it)) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    /** Retries dropped/failed links, expires stuck attempts and restarts a quiet discovery. */
    private val maintenance = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            pending.entries.removeAll { now - it.value > PENDING_TIMEOUT_MS }
            discovered.entries
                .sortedByDescending { it.value.gateway }
                .forEach { maybeConnect(it.key) }
            if (connected.isEmpty() && now - lastDiscoveryRestart > DISCOVERY_RESTART_MS) {
                client.stopDiscovery()
                discovered.clear()
                startDiscovery()
            }
            main.postDelayed(this, MAINTENANCE_MS)
        }
    }

    private fun enforceAllowlist() {
        connected.entries.filter { !allowed(it.value) }.forEach { (id, info) ->
            client.disconnectFromEndpoint(id)
            connected.remove(id)
            channel.trySend(TransportEvent.LinkDown(id))
            log("dropped ${info.label}: not in allowlist")
        }
        discovered.keys.toList().forEach { maybeConnect(it) }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun allowed(remote: NodeInfo) = allowlist.isEmpty() || remote.label.lowercase() in allowlist

    private fun fail(endpointId: String, reason: String) {
        val count = (failures[endpointId]?.first ?: 0) + 1
        val delay = (2_000L shl count.coerceAtMost(5)).coerceAtMost(60_000L)
        failures[endpointId] = count to SystemClock.elapsedRealtime() + delay
        log("link to ${label(endpointId)} failed ($reason), retry in ${delay / 1000}s")
    }

    private fun label(endpointId: String) = (connected[endpointId] ?: discovered[endpointId])?.label ?: endpointId

    private fun log(message: String) {
        channel.trySend(TransportEvent.Log(message))
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun statusOf(e: Exception) = (e as? ApiException)?.statusCode ?: -1

    private fun describe(e: Exception) =
        (e as? ApiException)?.let { ConnectionsStatusCodes.getStatusCodeString(it.statusCode) } ?: (e.message ?: e.javaClass.simpleName)

    companion object {
        private const val YIELD_MS = 6_000L
        private const val MAINTENANCE_MS = 5_000L
        private const val PENDING_TIMEOUT_MS = 30_000L
        private const val DISCOVERY_RESTART_MS = 90_000L

        fun nameOf(info: NodeInfo) = "HN1|${info.nodeId}|${Labels.clean(info.label)}|${if (info.gateway) 1 else 0}"

        fun parseName(name: String?): NodeInfo? {
            val parts = name?.split('|') ?: return null
            if (parts.size != 4 || parts[0] != "HN1" || parts[1].isEmpty()) return null
            return NodeInfo(parts[1], parts[2], parts[3] == "1")
        }
    }
}
