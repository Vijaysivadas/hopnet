package app.hopnet.core.testing

import app.hopnet.core.crypto.HopCrypto
import app.hopnet.core.crypto.KeyPair
import app.hopnet.core.gateway.Uplink
import app.hopnet.core.mesh.MeshNode
import app.hopnet.core.mesh.MeshTransport
import app.hopnet.core.mesh.NodeInfo
import app.hopnet.core.mesh.TransportEvent
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.protocol.Ids
import app.hopnet.core.store.InMemoryEnvelopeStore
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Real libsodium on the JVM, so tests exercise the same primitives as the phones and server. */
object TestCrypto : HopCrypto {
    private val ls = LazySodiumJava(SodiumJava())

    override fun newSignKeyPair(): KeyPair {
        val pk = ByteArray(32)
        val sk = ByteArray(64)
        check(ls.cryptoSignKeypair(pk, sk))
        return KeyPair(pk, sk)
    }

    override fun newBoxKeyPair(): KeyPair {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        check(ls.cryptoBoxKeypair(pk, sk))
        return KeyPair(pk, sk)
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val sig = ByteArray(64)
        check(ls.cryptoSignDetached(sig, message, message.size.toLong(), secretKey))
        return sig
    }

    override fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray) =
        signature.size == 64 && publicKey.size == 32 && ls.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    override fun seal(message: ByteArray, recipientPk: ByteArray): ByteArray {
        val out = ByteArray(message.size + 48)
        check(ls.cryptoBoxSeal(out, message, message.size.toLong(), recipientPk))
        return out
    }

    override fun openSealed(cipher: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray? {
        if (cipher.size < 48) return null
        val out = ByteArray(cipher.size - 48)
        return if (ls.cryptoBoxSealOpen(out, cipher, cipher.size.toLong(), publicKey, secretKey)) out else null
    }
}

/** Phones wired together in memory. `link(a, b)` is two phones coming into Bluetooth range. */
class InMemoryTransport(val nodeId: String) : MeshTransport {
    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = channel.receiveAsFlow()
    private val links = ConcurrentHashMap<String, InMemoryTransport>()

    @Volatile
    var local: NodeInfo = NodeInfo(nodeId, nodeId, false)
        private set

    override fun start(local: NodeInfo) {
        this.local = local
    }

    override fun updateLocal(local: NodeInfo) {
        this.local = local
    }

    override fun send(linkId: String, bytes: ByteArray) {
        links[linkId]?.channel?.trySend(TransportEvent.Received(nodeId, bytes.copyOf()))
    }

    override fun stop() {
        links.keys.toList().forEach { id -> links.remove(id)?.let { other -> unlink(this, other) } }
    }

    companion object {
        fun link(a: InMemoryTransport, b: InMemoryTransport) {
            a.links[b.nodeId] = b
            b.links[a.nodeId] = a
            a.channel.trySend(TransportEvent.LinkUp(b.nodeId, b.local))
            b.channel.trySend(TransportEvent.LinkUp(a.nodeId, a.local))
        }

        fun unlink(a: InMemoryTransport, b: InMemoryTransport) {
            a.links.remove(b.nodeId)
            b.links.remove(a.nodeId)
            a.channel.trySend(TransportEvent.LinkDown(b.nodeId))
            b.channel.trySend(TransportEvent.LinkDown(a.nodeId))
        }
    }
}

/** Stand-in for the server connection: records uploads, lets a test push DOWN/RECEIPT envelopes. */
class FakeUplink(online: Boolean = true) : Uplink {
    override val connected = MutableStateFlow(online)
    private val channel = Channel<Envelope>(Channel.UNLIMITED)
    override val incoming: Flow<Envelope> = channel.receiveAsFlow()
    val uploaded = CopyOnWriteArrayList<Envelope>()

    override fun upload(envelopes: List<Envelope>): Boolean {
        uploaded += envelopes
        return true
    }

    fun push(envelope: Envelope) {
        channel.trySend(envelope)
    }
}

class TestPhone(val name: String, scope: CoroutineScope, val uplink: Uplink? = null) {
    val transport = InMemoryTransport(name)
    val store = InMemoryEnvelopeStore()
    val node = MeshNode(store, transport, scope, nodeId = name, label = name, crypto = TestCrypto)

    init {
        uplink?.let(node::attachUplink)
        node.startRadio()
    }

    infix fun linkTo(other: TestPhone) = InMemoryTransport.link(transport, other.transport)
}

fun upEnvelope(maxHops: Int = Envelope.DEFAULT_MAX_HOPS, ttlMs: Long = 60_000, now: Long = System.currentTimeMillis()) = Envelope(
    type = EnvelopeType.UP,
    flags = Envelope.FLAG_TRACE,
    msgId = Ids.newMsgId(),
    dest = Ids.ZERO,
    hops = 0,
    maxHops = maxHops,
    createdAt = now,
    expiresAt = now + ttlMs,
    body = ByteArray(300) { it.toByte() },
)

/** Polls [check] until it returns non-null (real time; the mesh runs on real dispatchers). */
suspend fun <T : Any> eventually(timeoutMs: Long = 5_000, what: String = "condition", check: suspend () -> T?): T =
    try {
        withTimeout(timeoutMs) {
            var result = check()
            while (result == null) {
                delay(20)
                result = check()
            }
            result
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for $what", e)
    }
