package app.hopnet.core.mesh

import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.protocol.Ids
import app.hopnet.core.protocol.ReceiptStatus
import app.hopnet.core.protocol.Receipts
import app.hopnet.core.testing.FakeUplink
import app.hopnet.core.testing.TestCrypto
import app.hopnet.core.testing.TestPhone
import app.hopnet.core.testing.eventually
import app.hopnet.core.testing.upEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-phone scenarios on in-memory links: the hopping rules without any hardware. */
class MeshRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun phone(name: String, uplink: FakeUplink? = null) = TestPhone(name, scope, uplink)

    @Test
    fun messageHopsAlongALineToTheGateway() = runBlocking<Unit> {
        val server = FakeUplink(online = true)
        val a = phone("A"); val b = phone("B"); val c = phone("C"); val g = phone("G", server)
        a linkTo b; b linkTo c; c linkTo g
        eventually(what = "links") { a.node.peers.value.takeIf { it.size == 1 && g.node.isGateway.value } }

        val env = upEnvelope()
        a.node.originate(env)

        val uploaded = eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        assertEquals(3, uploaded.hops)
        assertEquals(listOf("A", "B", "C", "G"), uploaded.trace)
    }

    @Test
    fun duplicatesAreFilteredInADiamond() = runBlocking<Unit> {
        val server = FakeUplink(online = true)
        val a = phone("A"); val b = phone("B"); val c = phone("C"); val d = phone("D", server)
        a linkTo b; a linkTo c; b linkTo d; c linkTo d
        eventually(what = "links") { d.node.peers.value.takeIf { it.size == 2 && a.node.peers.value.size == 2 } }

        val env = upEnvelope()
        a.node.originate(env)
        eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        delay(300)
        assertEquals(1, server.uploaded.count { it.msgId == env.msgId })
        assertTrue(d.node.stats.value.router.duplicates >= 1)
    }

    @Test
    fun hopLimitStopsForwarding() = runBlocking<Unit> {
        val server = FakeUplink(online = true)
        val a = phone("A"); val b = phone("B"); val c = phone("C"); val g = phone("G", server)
        a linkTo b; b linkTo c; c linkTo g
        eventually(what = "links") { c.node.peers.value.takeIf { it.size == 2 } }

        a.node.originate(upEnvelope(maxHops = 2))
        eventually(what = "C holds it") { c.node.stats.value.stored.takeIf { it == 1 } }
        delay(300)
        assertTrue(server.uploaded.isEmpty())
    }

    @Test
    fun storeAndForwardWhenAPathAppearsLater() = runBlocking<Unit> {
        val server = FakeUplink(online = true)
        val a = phone("A"); val b = phone("B"); val g = phone("G", server)
        val env = upEnvelope()
        a.node.originate(env) // nobody around: it waits in A's store
        delay(200)
        a linkTo b // B walks past A, picks it up...
        eventually(what = "B carries it") { b.node.stats.value.stored.takeIf { it == 1 } }
        b linkTo g // ...and later meets a gateway
        val uploaded = eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        assertEquals(listOf("A", "B", "G"), uploaded.trace)
    }

    @Test
    fun gatewayComingOnlineUploadsWhatItHolds() = runBlocking<Unit> {
        val server = FakeUplink(online = false)
        val a = phone("A"); val g = phone("G", server)
        a linkTo g
        val env = upEnvelope()
        a.node.originate(env)
        eventually(what = "G holds it") { g.node.stats.value.stored.takeIf { it == 1 } }
        assertTrue(server.uploaded.isEmpty())
        server.connected.value = true
        val uploaded = eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        assertEquals(listOf("A", "G"), uploaded.trace)
    }

    @Test
    fun signedReceiptPurgesCopiesAndTellsTheSender() = runBlocking<Unit> {
        val serverKey = TestCrypto.newSignKeyPair()
        val server = FakeUplink(online = true)
        val a = phone("A"); val b = phone("B"); val g = phone("G", server)
        listOf(a, b, g).forEach { it.node.setIdentity(null, serverKey.publicKey, TestCrypto) }
        a linkTo b; b linkTo g
        eventually(what = "links") { b.node.peers.value.takeIf { it.size == 2 } }

        val receiptSeen = async { a.node.receipts.first() }
        val env = upEnvelope()
        a.node.originate(env)
        val up = eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }

        server.push(Receipts.build(env.msgId, ReceiptStatus.RECEIVED, up.hops, up.trace, serverKey.secretKey, TestCrypto, System.currentTimeMillis()))
        val event = receiptSeen.await()
        assertTrue(event.mine)
        assertEquals(2, event.receipt.hops)
        assertEquals(listOf("A", "B", "G"), event.receipt.path)
        for (p in listOf(a, b, g)) {
            eventually(what = "${p.name} purged") {
                p.store.live(System.currentTimeMillis(), 100).takeIf { list -> list.none { it.envelope.msgId == env.msgId } }
            }
        }
    }

    @Test
    fun forgedReceiptIsIgnored() = runBlocking<Unit> {
        val serverKey = TestCrypto.newSignKeyPair()
        val attacker = TestCrypto.newSignKeyPair()
        val a = phone("A"); val b = phone("B")
        listOf(a, b).forEach { it.node.setIdentity(null, serverKey.publicKey, TestCrypto) }
        a linkTo b
        val env = upEnvelope()
        a.node.originate(env)
        eventually(what = "B carries it") { b.node.stats.value.stored.takeIf { it == 1 } }

        val forged = Receipts.build(env.msgId, ReceiptStatus.RECEIVED, 1, emptyList(), attacker.secretKey, TestCrypto, System.currentTimeMillis())
        b.node.originate(forged.let { Envelope(EnvelopeType.RECEIPT, 0, it.msgId, it.dest, 0, 12, it.createdAt, it.expiresAt, it.body) })
        delay(300)
        assertTrue(a.store.live(System.currentTimeMillis(), 100).any { it.envelope.msgId == env.msgId })
        assertEquals(1, a.node.stats.value.router.dropped)
    }

    @Test
    fun downEnvelopeFindsItsAddressee() = runBlocking<Unit> {
        val server = FakeUplink(online = true)
        val g = phone("G", server); val b = phone("B"); val r = phone("R")
        val ravi = Ids.newMsgId()
        r.node.setIdentity(ravi, null, null)
        g linkTo b; b linkTo r
        eventually(what = "links") { b.node.peers.value.takeIf { it.size == 2 } }

        val delivered = async { r.node.deliveries.first() }
        val now = System.currentTimeMillis()
        val down = Envelope(EnvelopeType.DOWN, Envelope.FLAG_TRACE, Ids.newMsgId(), ravi, 0, 12, now, now + 60_000, ByteArray(64), listOf("cloud"))
        server.push(down)
        val got = delivered.await()
        assertEquals(down.msgId, got.msgId)
        assertEquals(listOf("cloud", "G", "B", "R"), got.trace)
        assertFalse(b.store.live(now, 10).isEmpty()) // relays keep a copy until the DELIVERED receipt
        assertEquals(0, r.node.stats.value.stored)
    }
}
