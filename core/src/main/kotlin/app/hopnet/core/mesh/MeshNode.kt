package app.hopnet.core.mesh

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.gateway.Uplink
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.protocol.Frame
import app.hopnet.core.protocol.FrameCodec
import app.hopnet.core.protocol.Labels
import app.hopnet.core.protocol.Receipt
import app.hopnet.core.store.EnvelopeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One phone's relay engine. A single coroutine consumes every event (link up/down, frames,
 * server traffic, local sends) in order, so the [Router] never needs locks.
 * Works without a registered user: an unregistered phone is still a useful relay.
 */
class MeshNode(
    private val store: EnvelopeStore,
    private val transport: MeshTransport,
    private val scope: CoroutineScope,
    val nodeId: String,
    label: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    housekeepingIntervalMs: Long = 60_000,
    /** Lets this phone verify (and so relay) broadcasts even before its user registers. */
    crypto: HopCrypto? = null,
) {
    data class Peer(
        val linkId: String,
        val nodeId: String,
        val label: String,
        val gateway: Boolean,
        val connectedAt: Long,
        val rx: Int = 0,
        val tx: Int = 0,
    )

    data class Stats(val stored: Int = 0, val router: RouterStats = RouterStats())

    data class LogLine(val at: Long, val text: String)

    data class ReceiptEvent(val receipt: Receipt, val mine: Boolean)

    data class OwnSent(val msgId: String, val peers: Int, val viaUplink: Boolean)

    private sealed interface Event {
        data class Transport(val event: TransportEvent) : Event
        data class FromUplink(val envelope: Envelope) : Event
        data class GatewayChanged(val on: Boolean) : Event
        data class Originate(val envelope: Envelope, val done: CompletableDeferred<Unit>) : Event
        data class SetIdentity(val address: String?, val serverSignPk: ByteArray?, val crypto: HopCrypto?) : Event
        data class SetLabel(val label: String) : Event
        data class Reset(val done: CompletableDeferred<Unit>) : Event
        data object StartRadio : Event
        data object StopRadio : Event
        data object Housekeeping : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val router = Router(store, Output(), clock, crypto).also { it.label = Labels.clean(label) }
    private var uplink: Uplink? = null
    private val peerMap = LinkedHashMap<String, Peer>()

    private val _label = MutableStateFlow(Labels.clean(label))
    val label: StateFlow<String> = _label.asStateFlow()
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private val _isGateway = MutableStateFlow(false)
    val isGateway: StateFlow<Boolean> = _isGateway.asStateFlow()
    private val _radioOn = MutableStateFlow(false)
    val radioOn: StateFlow<Boolean> = _radioOn.asStateFlow()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _deliveries = MutableSharedFlow<Envelope>(extraBufferCapacity = 1024)
    /** DOWN envelopes addressed to this phone's user. */
    val deliveries: SharedFlow<Envelope> = _deliveries.asSharedFlow()
    private val _broadcasts = MutableSharedFlow<Envelope>(extraBufferCapacity = 1024)
    /** Verified BROADCAST envelopes from other phones, each newer than the last one from that sender. */
    val broadcasts: SharedFlow<Envelope> = _broadcasts.asSharedFlow()
    private val _receipts = MutableSharedFlow<ReceiptEvent>(extraBufferCapacity = 1024)
    val receipts: SharedFlow<ReceiptEvent> = _receipts.asSharedFlow()
    private val _ownSent = MutableSharedFlow<OwnSent>(extraBufferCapacity = 1024)
    val ownSent: SharedFlow<OwnSent> = _ownSent.asSharedFlow()

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (t: Throwable) {
                    if (!isActive) throw t
                    appendLog("error handling ${event::class.simpleName}: ${t.message}")
                }
            }
        }
        scope.launch { transport.events.collect { events.send(Event.Transport(it)) } }
        scope.launch {
            while (isActive) {
                delay(housekeepingIntervalMs)
                events.send(Event.Housekeeping)
            }
        }
    }

    // ---- public API (thread-safe: everything goes through the event channel) ---------------

    fun startRadio() {
        events.trySend(Event.StartRadio)
    }

    fun stopRadio() {
        events.trySend(Event.StopRadio)
    }

    fun setLabel(label: String) {
        events.trySend(Event.SetLabel(label))
    }

    /** Lets the node recognise DOWN envelopes for its user and verify server receipts. */
    fun setIdentity(address: String?, serverSignPk: ByteArray?, crypto: HopCrypto?) {
        events.trySend(Event.SetIdentity(address, serverSignPk, crypto))
    }

    /** Connects the server link. While it is connected this node acts as a gateway. */
    fun attachUplink(uplink: Uplink) {
        this.uplink = uplink
        scope.launch { uplink.connected.collect { events.send(Event.GatewayChanged(it)) } }
        scope.launch { uplink.incoming.collect { events.send(Event.FromUplink(it)) } }
    }

    suspend fun originate(envelope: Envelope) {
        val done = CompletableDeferred<Unit>()
        events.send(Event.Originate(envelope, done))
        done.await()
    }

    /** createdAt of the newest live broadcast held from [address] (e.g. this user's own last one). */
    suspend fun latestBroadcastAt(address: String): Long? =
        store.liveOfType(EnvelopeType.BROADCAST, clock()).filter { it.envelope.dest == address }.maxOfOrNull { it.envelope.createdAt }

    suspend fun resetStore() {
        val done = CompletableDeferred<Unit>()
        events.send(Event.Reset(done))
        done.await()
    }

    // ---- event handling -------------------------------------------------------------------

    private fun localInfo() = NodeInfo(nodeId, router.label, router.isGateway)

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Transport -> onTransport(event.event)
            is Event.FromUplink -> router.onFromUplink(event.envelope)
            is Event.GatewayChanged -> {
                router.setGateway(event.on)
                _isGateway.value = event.on
                if (_radioOn.value) transport.updateLocal(localInfo())
                broadcastHello()
            }
            is Event.Originate -> try {
                router.originate(event.envelope)
            } finally {
                event.done.complete(Unit)
            }
            is Event.SetIdentity -> {
                router.myAddress = event.address
                router.setReceiptVerifier(event.serverSignPk, event.crypto)
            }
            is Event.SetLabel -> {
                router.label = Labels.clean(event.label)
                _label.value = router.label
                if (_radioOn.value) transport.updateLocal(localInfo())
                broadcastHello()
            }
            is Event.Reset -> try {
                store.clear()
                appendLog("mesh store cleared")
            } finally {
                event.done.complete(Unit)
            }
            Event.StartRadio -> if (!_radioOn.value) {
                _radioOn.value = true
                transport.start(localInfo())
                appendLog("radio on as ${router.label} ($nodeId)")
            }
            Event.StopRadio -> if (_radioOn.value) {
                _radioOn.value = false
                transport.stop()
                peerMap.keys.toList().forEach { router.onLinkDown(it) }
                peerMap.clear()
                appendLog("radio off")
            }
            Event.Housekeeping -> router.housekeeping()
        }
        publish()
    }

    private suspend fun onTransport(event: TransportEvent) {
        when (event) {
            is TransportEvent.LinkUp -> {
                peerMap[event.linkId] = Peer(event.linkId, event.remote.nodeId, event.remote.label, event.remote.gateway, clock())
                appendLog("link up: ${event.remote.label}${if (event.remote.gateway) " [gateway]" else ""}")
                transport.send(event.linkId, FrameCodec.encode(Frame.Hello(nodeId, router.label, router.isGateway)))
                router.onLinkUp(event.linkId)
            }
            is TransportEvent.LinkDown -> {
                val peer = peerMap.remove(event.linkId)
                router.onLinkDown(event.linkId)
                if (peer != null) appendLog("link down: ${peer.label}")
            }
            is TransportEvent.Received -> {
                val peer = peerMap[event.linkId] ?: return
                peerMap[event.linkId] = peer.copy(rx = peer.rx + 1)
                when (val frame = FrameCodec.decode(event.bytes)) {
                    is Frame.Hello -> peerMap[event.linkId] =
                        peer.copy(nodeId = frame.nodeId, label = frame.label, gateway = frame.gateway, rx = peer.rx + 1)
                    is Frame.Env -> {
                        val env = frame.envelope
                        appendLog("← ${peer.label}: ${env.type} ${env.shortId} hop ${env.hops + 1}")
                        router.onFromLink(env, event.linkId)
                    }
                    null -> appendLog("← ${peer.label}: unreadable frame (${event.bytes.size} B)")
                }
            }
            is TransportEvent.Log -> appendLog(event.message)
        }
    }

    private fun broadcastHello() {
        val hello = FrameCodec.encode(Frame.Hello(nodeId, router.label, router.isGateway))
        peerMap.keys.forEach { transport.send(it, hello) }
    }

    private suspend fun publish() {
        _peers.value = peerMap.values.toList()
        _stats.value = Stats(store.count(), router.stats)
    }

    /** Adds a line to the live log shown on the Network screen. Safe from any thread. */
    fun log(text: String) = appendLog(text)

    private fun appendLog(text: String) {
        val line = LogLine(clock(), text)
        _log.update { (it + line).takeLast(LOG_LINES) }
    }

    private inner class Output : RouterOutput {
        override fun sendToLink(linkId: String, envelope: Envelope) {
            transport.send(linkId, FrameCodec.encode(Frame.Env(envelope)))
            peerMap[linkId]?.let { peer ->
                peerMap[linkId] = peer.copy(tx = peer.tx + 1)
                appendLog("→ ${peer.label}: ${envelope.type} ${envelope.shortId}")
            }
        }

        override fun uplink(envelopes: List<Envelope>) {
            val ok = uplink?.upload(envelopes) == true
            appendLog(
                if (ok) "⇧ server: ${envelopes.joinToString { "${it.type} ${it.shortId}" }}"
                else "⇧ upload failed, kept ${envelopes.size} in store"
            )
        }

        override fun deliverLocal(envelope: Envelope) {
            _deliveries.tryEmit(envelope)
        }

        override fun deliverBroadcast(envelope: Envelope) {
            _broadcasts.tryEmit(envelope)
        }

        override fun onReceipt(receipt: Receipt, mine: Boolean) {
            appendLog("receipt ${receipt.status} for ${receipt.targetId.take(6)}${if (mine) " (mine)" else ""}")
            _receipts.tryEmit(ReceiptEvent(receipt, mine))
        }

        override fun onOwnSent(msgId: String, peers: Int, viaUplink: Boolean) {
            _ownSent.tryEmit(OwnSent(msgId, peers, viaUplink))
        }

        override fun log(message: String) = appendLog(message)
    }

    companion object {
        const val LOG_LINES = 300
    }
}
