package app.hopnet.core.gateway

import app.hopnet.core.client.HopnetJson
import app.hopnet.core.protocol.B64
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The node's path to the server. While [connected] is true the node is a gateway. */
interface Uplink {
    val connected: StateFlow<Boolean>
    /** DOWN envelopes and RECEIPTs pushed by the server, to deliver or flood into the mesh. */
    val incoming: Flow<Envelope>
    fun upload(envelopes: List<Envelope>): Boolean
}

@Serializable
data class GatewayHello(val t: String = "hello", val node: String, val label: String, val addr: String? = null)

@Serializable
data class GatewayUp(val t: String = "up", val envs: List<String>)

/**
 * WebSocket session to `/v1/gateway/ws`. Retries with back-off whenever it is enabled
 * (i.e. Android reports a network), so "has internet" really means "can reach our server".
 */
class GatewayClient(
    private val scope: CoroutineScope,
    private val http: OkHttpClient = defaultHttpClient(),
) : Uplink {
    @Volatile
    var serverUrl: String? = null

    @Volatile
    var hello: GatewayHello = GatewayHello(node = "", label = "")
        set(value) {
            field = value
            if (_connected.value) socket?.send(HopnetJson.encodeToString(GatewayHello.serializer(), value))
        }

    var onLog: (String) -> Unit = {}

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val inbox = Channel<Envelope>(Channel.UNLIMITED)
    override val incoming: Flow<Envelope> = inbox.receiveAsFlow()

    @Volatile
    private var socket: WebSocket? = null
    private var loop: Job? = null

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (loop?.isActive == true) return
            loop = scope.launch { connectLoop() }
        } else {
            loop?.cancel()
            loop = null
            socket?.close(1000, "offline")
            socket = null
            _connected.value = false
        }
    }

    /** Drop the current session (e.g. server URL changed); the loop reconnects. */
    fun reconnect() {
        socket?.close(1000, "reconnect")
    }

    override fun upload(envelopes: List<Envelope>): Boolean {
        val ws = socket ?: return false
        if (!_connected.value || envelopes.isEmpty()) return false
        val msg = GatewayUp(envs = envelopes.map { B64.encode(EnvelopeCodec.encode(it)) })
        return ws.send(HopnetJson.encodeToString(GatewayUp.serializer(), msg))
    }

    private suspend fun connectLoop() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive) {
            val base = serverUrl
            if (base.isNullOrBlank()) {
                delay(2_000)
                continue
            }
            val opened = runSession(base)
            backoff = if (opened) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoff)
        }
    }

    /** Runs one WebSocket session to completion; returns whether it ever opened. */
    private suspend fun runSession(base: String): Boolean {
        val finished = CompletableDeferred<Boolean>()
        var opened = false
        val request = try {
            Request.Builder().url(wsUrl(base)).build()
        } catch (e: IllegalArgumentException) {
            onLog("bad server URL: $base")
            return false
        }
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                socket = webSocket
                webSocket.send(HopnetJson.encodeToString(GatewayHello.serializer(), hello))
                _connected.value = true
                onLog("server connected: $base")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = HopnetJson.parseToJsonElement(text).jsonObject
                    if (obj["t"]?.jsonPrimitive?.content == "down") {
                        obj["envs"]?.jsonArray?.forEach { item ->
                            EnvelopeCodec.decodeOrNull(B64.decode(item.jsonPrimitive.content))?.let { inbox.trySend(it) }
                        }
                    }
                }.onFailure { onLog("bad server message: ${it.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = done(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (opened) onLog("server link lost: ${t.message}")
                done(webSocket)
            }

            private fun done(webSocket: WebSocket) {
                if (socket === webSocket) {
                    socket = null
                    _connected.value = false
                }
                finished.complete(opened)
            }
        })
        return try {
            finished.await()
        } finally {
            if (!finished.isCompleted) ws.cancel()
        }
    }

    companion object {
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 15_000L

        fun wsUrl(base: String): String {
            val trimmed = base.trim().trimEnd('/')
            val ws = when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> trimmed
            }
            return "$ws/v1/gateway/ws"
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
