package app.hopnet.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.hopnet.core.client.HopnetClient
import app.hopnet.core.client.IncomingBroadcast
import app.hopnet.core.client.IncomingMessage
import app.hopnet.core.client.OutgoingUpdate
import app.hopnet.core.client.ServerApi
import app.hopnet.core.client.UserInfo
import app.hopnet.core.crypto.Identity
import app.hopnet.core.crypto.ServerKeys
import app.hopnet.core.gateway.GatewayClient
import app.hopnet.core.gateway.GatewayHello
import app.hopnet.core.mesh.MeshNode
import app.hopnet.core.protocol.B64
import app.hopnet.core.protocol.Ids
import app.hopnet.core.protocol.Labels
import app.hopnet.sdk.crypto.SodiumCrypto
import app.hopnet.sdk.identity.HopnetProfile
import app.hopnet.sdk.identity.IdentityVault
import app.hopnet.sdk.identity.SettingsStore
import app.hopnet.sdk.nearby.NearbyTransport
import app.hopnet.sdk.net.ConnectivityMonitor
import app.hopnet.sdk.service.MeshService
import app.hopnet.sdk.store.MeshDatabase
import app.hopnet.sdk.store.RoomEnvelopeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Snapshot of the mesh for UI: who is nearby, whether we are a gateway, what we carry. */
data class MeshStatus(
    val registered: Boolean = false,
    val serviceRunning: Boolean = false,
    val radioOn: Boolean = false,
    val networkAvailable: Boolean = false,
    val gateway: Boolean = false,
    val peers: List<MeshNode.Peer> = emptyList(),
    val stored: Int = 0,
    val stats: MeshNode.Stats = MeshNode.Stats(),
    val label: String = "",
    val nodeId: String = "",
    val allowlist: Set<String> = emptySet(),
)

/**
 * Hopnet SDK entry point. Any app can make its service reachable offline with:
 *
 *   Hopnet.init(context)                         // Application.onCreate
 *   Hopnet.register(serverUrl, handle, name)     // once, while online
 *   Hopnet.startMesh(context)                    // after permissions are granted
 *   Hopnet.send("chat", "message", data)         // goes up via mesh or internet
 *   Hopnet.incoming.collect { ... }              // replies, verified and decrypted
 *   Hopnet.broadcast("findme", "lost", data)     // to every phone nearby, no server needed
 *   Hopnet.broadcasts.collect { ... }            // other phones' broadcasts, verified
 */
object Hopnet {
    /** Every app embedding the SDK must use the same id so they form one shared mesh. */
    const val SERVICE_ID = "app.hopnet.mesh.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: SettingsStore
    private lateinit var crypto: SodiumCrypto
    private lateinit var identity: Identity
    private lateinit var transport: NearbyTransport
    private lateinit var node: MeshNode
    private lateinit var gateway: GatewayClient
    private lateinit var connectivity: ConnectivityMonitor
    private val api = ServerApi()

    private var client: HopnetClient? = null
    private var clientJob: Job? = null

    private val _profile = MutableStateFlow<HopnetProfile?>(null)
    val profile: StateFlow<HopnetProfile?> = _profile.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 256)
    /** Verified, decrypted messages from the server for this user, from every service. */
    val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    private val _updates = MutableSharedFlow<OutgoingUpdate>(extraBufferCapacity = 256)
    /** Progress of things this phone sent: passed to peers, reached server, rejected. */
    val updates: SharedFlow<OutgoingUpdate> = _updates.asSharedFlow()

    private val _broadcasts = MutableSharedFlow<IncomingBroadcast>(extraBufferCapacity = 256)
    /** Verified broadcasts from other phones in the mesh (e.g. Find Me alerts), from every service. */
    val broadcasts: SharedFlow<IncomingBroadcast> = _broadcasts.asSharedFlow()

    private val serviceRunning = MutableStateFlow(false)
    private val allowlist = MutableStateFlow<Set<String>>(emptySet())
    lateinit var status: StateFlow<MeshStatus>
        private set

    val log: StateFlow<List<MeshNode.LogLine>> get() = node.log
    val nodeId: String get() = settings.nodeId
    val address: String get() = identity.address
    val savedServerUrl: String? get() = settings.serverUrl

    @Synchronized
    fun init(context: Context) {
        if (::node.isInitialized) return
        val app = context.applicationContext
        settings = SettingsStore(app)
        crypto = SodiumCrypto()
        identity = IdentityVault(app, crypto).loadOrCreate()
        transport = NearbyTransport(app, SERVICE_ID).also { it.allowlist = settings.allowlist }
        allowlist.value = settings.allowlist
        node = MeshNode(RoomEnvelopeStore(MeshDatabase.create(app)), transport, scope, settings.nodeId, settings.label, crypto = crypto)
        gateway = GatewayClient(scope).also { it.onLog = node::log }
        node.attachUplink(gateway)
        connectivity = ConnectivityMonitor(app)

        // A profile only belongs to the keys it was registered with.
        val saved = settings.profile?.takeIf { it.address == identity.address }
        if (saved == null) settings.profile = null
        _profile.value = saved
        gateway.serverUrl = saved?.serverUrl ?: settings.serverUrl
        refreshHello()
        saved?.let(::startClient)

        // Any time Android has a network, try to reach the server: that makes us a gateway.
        scope.launch { connectivity.online.collect { gateway.setEnabled(it) } }

        val meshPart = combine(node.peers, node.isGateway, node.stats, node.radioOn, node.label) { peers, gw, stats, radio, label ->
            MeshStatus(gateway = gw, peers = peers, stored = stats.stored, stats = stats, radioOn = radio, label = label)
        }
        status = combine(meshPart, connectivity.online, serviceRunning, _profile, allowlist) { m, online, running, profile, allow ->
            m.copy(networkAvailable = online, serviceRunning = running, registered = profile != null, nodeId = settings.nodeId, allowlist = allow)
        }.stateIn(scope, SharingStarted.Eagerly, MeshStatus(label = settings.label, nodeId = settings.nodeId))
    }

    // ---- registration ------------------------------------------------------------------------

    /** Claims [handle] on the server (needs internet once). Afterwards everything works offline. */
    suspend fun register(serverUrl: String, handle: String, name: String): HopnetProfile {
        val url = serverUrl.trim().trimEnd('/')
        val resp = api.register(url, handle.trim().removePrefix("@").lowercase(), name.trim(), identity, crypto)
        val profile = HopnetProfile(url, resp.handle, resp.name, resp.address, resp.server.signPk, resp.server.encPk)
        settings.serverUrl = url
        settings.profile = profile
        _profile.value = profile
        gateway.serverUrl = url
        refreshHello()
        gateway.reconnect()
        startClient(profile)
        return profile
    }

    suspend fun lookupUser(handle: String): UserInfo? {
        val url = _profile.value?.serverUrl ?: return null
        return api.lookup(url, handle)
    }

    private fun startClient(profile: HopnetProfile) {
        clientJob?.cancel()
        client?.close()
        val keys = ServerKeys(B64.decode(profile.serverSignPk), B64.decode(profile.serverEncPk))
        val c = HopnetClient(node, crypto, identity, keys, scope).also { it.start() }
        client = c
        clientJob = scope.launch {
            launch { c.incoming.collect { _incoming.emit(it) } }
            launch { c.updates.collect { _updates.emit(it) } }
            launch { c.broadcasts.collect { _broadcasts.emit(it) } }
        }
    }

    // ---- sending -----------------------------------------------------------------------------

    fun newMessageId(): String = Ids.newMsgId()

    /**
     * Sends a request for [service] to the server. Returns immediately once it is stored on
     * this phone; it then travels by whatever path appears. Track it via [updates].
     * [ttlMs] is how long the request stays worth delivering (default 24 h): a weather question
     * that reaches the server a day late is useless, so such services pass less.
     */
    suspend fun send(service: String, kind: String, data: JsonObject, msgId: String = newMessageId(), ttlMs: Long? = null): String {
        val c = client ?: throw IllegalStateException("Register with a Hopnet server first")
        return if (ttlMs == null) c.send(service, kind, data, msgId) else c.send(service, kind, data, msgId, ttlMs)
    }

    /**
     * Sends a signed message every Hopnet phone nearby can read, hop by hop, with no server
     * needed. It replaces this user's previous broadcast everywhere. Gateways also upload it to
     * the server; [updates] reports Forwarded and AtServer for it like for [send].
     */
    suspend fun broadcast(service: String, kind: String, data: JsonObject, msgId: String = newMessageId()): String {
        val c = client ?: throw IllegalStateException("Register with a Hopnet server first")
        return c.broadcast(service, kind, data, msgId)
    }

    // ---- mesh lifecycle ----------------------------------------------------------------------

    /**
     * Nearby discovery still fails with MISSING_PERMISSION_ACCESS_COARSE_LOCATION on Android 16
     * without location (verified on an API 36 emulator), so location is requested on every version.
     */
    fun requiredPermissions(): Array<String> {
        val location = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val bluetooth = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        return when {
            Build.VERSION.SDK_INT >= 33 ->
                bluetooth + Manifest.permission.NEARBY_WIFI_DEVICES + location + Manifest.permission.POST_NOTIFICATIONS
            Build.VERSION.SDK_INT >= 31 -> bluetooth + location
            else -> location
        }.toTypedArray()
    }

    /** Bluetooth scanning needs the system Location switch on, not just the permission. */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(android.location.LocationManager::class.java) ?: return true
        return androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }

    fun hasPermissions(context: Context) = requiredPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Starts the background relay (foreground service). Requires [hasPermissions]. */
    fun startMesh(context: Context) = MeshService.start(context)

    fun stopMesh(context: Context) = MeshService.stop(context)

    internal fun onServiceStarted() {
        serviceRunning.value = true
        node.startRadio()
    }

    internal fun onServiceStopped() {
        node.stopRadio()
        serviceRunning.value = false
    }

    // ---- settings & demo controls -----------------------------------------------------------

    /** Name shown to nearby phones and in hop paths ("A", "Ravi-phone"...). */
    fun setLabel(label: String) {
        settings.label = label
        node.setLabel(settings.label)
        refreshHello()
    }

    /** Demo topology control: only link with these labels (empty = link with anyone). */
    fun setAllowlist(labels: Set<String>) {
        val clean = labels.map { Labels.clean(it) }.filter { it.isNotBlank() }.toSet()
        settings.allowlist = clean
        allowlist.value = clean
        transport.allowlist = clean
    }

    suspend fun resetMeshStore() = node.resetStore()

    fun log(message: String) = node.log(message)

    private fun refreshHello() {
        gateway.hello = GatewayHello(node = settings.nodeId, label = settings.label, addr = _profile.value?.address)
    }
}
