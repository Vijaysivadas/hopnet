package app.hopnet.core.mesh

import app.hopnet.core.client.HopnetClient
import app.hopnet.core.client.IncomingBroadcast
import app.hopnet.core.client.OutgoingUpdate
import app.hopnet.core.crypto.Identity
import app.hopnet.core.crypto.ServerKeys
import app.hopnet.core.protocol.BroadcastInner
import app.hopnet.core.protocol.Broadcasts
import app.hopnet.core.protocol.Envelope
import app.hopnet.core.protocol.EnvelopeType
import app.hopnet.core.protocol.Ids
import app.hopnet.core.protocol.ReceiptStatus
import app.hopnet.core.protocol.Receipts
import app.hopnet.core.testing.FakeUplink
import app.hopnet.core.testing.TestCrypto
import app.hopnet.core.testing.TestPhone
import app.hopnet.core.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Find Me's transport: signed broadcasts that every phone shows, with or without a server. */
class BroadcastRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun phone(name: String, uplink: FakeUplink? = null) = TestPhone(name, scope, uplink)

    /** Everything a phone's app would be shown, in order. */
    private fun TestPhone.shown(): MutableList<Envelope> =
        CopyOnWriteArrayList<Envelope>().also { list -> scope.launch { node.broadcasts.collect { list += it } } }

    private suspend fun TestPhone.heldBroadcasts() =
        store.live(System.currentTimeMillis(), 100).filter { it.envelope.type == EnvelopeType.BROADCAST }.map { it.envelope.msgId }

    private var clock = System.currentTimeMillis()

    private fun lost(who: Identity, kind: String = "lost", lat: Double = 9.4346, ts: Long = ++clock) = Broadcasts.build(
        BroadcastInner(
            id = Ids.newMsgId(), from = who.address, ts = ts, exp = ts + 60_000, svc = "findme", kind = kind,
            data = JsonObject(mapOf("name" to JsonPrimitive("Ravi"), "lat" to JsonPrimitive(lat), "lon" to JsonPrimitive(77.0817))),
        ),
        who.sign, TestCrypto,
    )

    @Test
    fun reachesEveryPhoneWithNoServerAtAll() = runBlocking<Unit> {
        val a = phone("A"); val b = phone("B"); val c = phone("C")
        val seenB = b.shown(); val seenC = c.shown()
        a linkTo b; b linkTo c
        eventually(what = "links") { b.node.peers.value.takeIf { it.size == 2 } }

        val env = lost(Identity.generate(TestCrypto))
        a.node.originate(env)

        val atC = eventually(what = "C shows it") { seenC.firstOrNull() }
        assertEquals(env.msgId, atC.msgId)
        assertEquals(listOf("A", "B", "C"), atC.trace)
        assertEquals(2, atC.hops)
        eventually(what = "B shows it") { seenB.firstOrNull() }
        delay(200)
        assertEquals(1, seenB.size)
        assertEquals(1, seenC.size)
    }

    @Test
    fun newerUpdateReplacesTheOldOneEverywhere() = runBlocking<Unit> {
        val ravi = Identity.generate(TestCrypto)
        val a = phone("A"); val b = phone("B"); val c = phone("C")
        val seenC = c.shown()
        a linkTo b; b linkTo c
        eventually(what = "links") { b.node.peers.value.takeIf { it.size == 2 } }

        val first = lost(ravi, lat = 9.4300)
        val second = lost(ravi, lat = 9.4310)
        a.node.originate(first)
        eventually(what = "first at C") { seenC.firstOrNull { it.msgId == first.msgId } }
        a.node.originate(second)
        eventually(what = "second at C") { seenC.firstOrNull { it.msgId == second.msgId } }

        for (p in listOf(a, b, c)) {
            eventually(what = "${p.name} holds only the update") { p.heldBroadcasts().takeIf { it == listOf(second.msgId) } }
        }
    }

    @Test
    fun aLateOldCopyNeverOverridesTheUpdate() = runBlocking<Unit> {
        val ravi = Identity.generate(TestCrypto)
        val a = phone("A"); val x = phone("X"); val c = phone("C")
        val seenC = c.shown()
        val first = lost(ravi, lat = 9.4300)
        val second = lost(ravi, kind = "found", lat = 9.4310)

        // X picks up the first alert, then walks away.
        a linkTo x
        a.node.originate(first)
        eventually(what = "X carries first") { x.heldBroadcasts().takeIf { it == listOf(first.msgId) } }
        x.transport.stop()
        eventually(what = "X alone") { x.node.peers.value.takeIf { it.isEmpty() } }

        // Ravi is found; C hears only the update.
        a.node.originate(second)
        a linkTo c
        eventually(what = "C shows found") { seenC.firstOrNull { it.msgId == second.msgId } }

        // X comes back with the stale alert: C ignores it and X learns the update.
        x linkTo c
        eventually(what = "X updated") { x.heldBroadcasts().takeIf { it == listOf(second.msgId) } }
        delay(300)
        assertTrue(seenC.none { it.msgId == first.msgId })
        assertEquals(listOf(second.msgId), c.heldBroadcasts())
    }

    @Test
    fun forgedOrTamperedBroadcastsAreDropped() = runBlocking<Unit> {
        val ravi = Identity.generate(TestCrypto)
        val attacker = Identity.generate(TestCrypto)
        val a = phone("A"); val b = phone("B")
        val seenB = b.shown()
        a linkTo b
        eventually(what = "link") { b.node.peers.value.takeIf { it.size == 1 } }

        // Signed by the attacker but claiming to be Ravi (dest = Ravi's address).
        val real = lost(attacker)
        val forged = Envelope(EnvelopeType.BROADCAST, real.flags, real.msgId, ravi.address, 0, real.maxHops, real.createdAt, real.expiresAt, real.body)
        // Ravi's own broadcast with its timestamp pushed forward by a relay.
        val genuine = lost(ravi)
        val retimed = Envelope(EnvelopeType.BROADCAST, genuine.flags, genuine.msgId, genuine.dest, 0, genuine.maxHops, genuine.createdAt + 1, genuine.expiresAt, genuine.body)
        a.node.originate(forged)
        a.node.originate(retimed)

        eventually(what = "B drops both") { b.node.stats.value.router.dropped.takeIf { it == 2 } }
        assertTrue(seenB.isEmpty())
        assertTrue(b.heldBroadcasts().isEmpty())
    }

    @Test
    fun gatewayUploadsAndStillFloodsAndTheReceiptKeepsItAlive() = runBlocking<Unit> {
        val serverKey = TestCrypto.newSignKeyPair()
        val server = FakeUplink(online = true)
        val a = phone("A"); val g = phone("G", server); val c = phone("C")
        listOf(a, g, c).forEach { it.node.setIdentity(null, serverKey.publicKey, TestCrypto) }
        val seenC = c.shown()
        val receipts = CopyOnWriteArrayList<MeshNode.ReceiptEvent>()
        scope.launch { a.node.receipts.collect { receipts += it } }
        a linkTo g; g linkTo c
        eventually(what = "links") { g.node.peers.value.takeIf { it.size == 2 && g.node.isGateway.value } }

        val env = lost(Identity.generate(TestCrypto))
        a.node.originate(env)
        val up = eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        assertEquals(listOf("A", "G"), up.trace)
        eventually(what = "C still gets it past the gateway") { seenC.firstOrNull { it.msgId == env.msgId } }

        server.push(Receipts.build(env.msgId, ReceiptStatus.RECEIVED, up.hops, up.trace, serverKey.secretKey, TestCrypto, System.currentTimeMillis()))
        val receipt = eventually(what = "A hears the server has it") { receipts.firstOrNull() }
        assertTrue(receipt.mine)
        delay(200)
        for (p in listOf(a, g, c)) assertEquals("${p.name} keeps it", listOf(env.msgId), p.heldBroadcasts())

        // Reconnecting doesn't upload it again: the server already has it.
        server.connected.value = false
        eventually(what = "offline") { g.node.isGateway.value.takeIf { !it } }
        server.connected.value = true
        eventually(what = "online") { g.node.isGateway.value.takeIf { it } }
        delay(200)
        assertEquals(1, server.uploaded.count { it.msgId == env.msgId })
    }

    @Test
    fun gatewayComingOnlineUploadsTheBroadcastsItCarries() = runBlocking<Unit> {
        val server = FakeUplink(online = false)
        val a = phone("A"); val g = phone("G", server)
        a linkTo g
        val env = lost(Identity.generate(TestCrypto))
        a.node.originate(env)
        eventually(what = "G carries it") { g.heldBroadcasts().takeIf { it == listOf(env.msgId) } }
        assertTrue(server.uploaded.isEmpty())
        server.connected.value = true
        val up = eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        assertEquals(listOf("A", "G"), up.trace)
    }

    @Test
    fun ownGatewayPhoneUploadsItsOwnBroadcastAndFloodsIt() = runBlocking<Unit> {
        val server = FakeUplink(online = true)
        val g = phone("G", server); val c = phone("C")
        val seenC = c.shown()
        g linkTo c
        eventually(what = "link") { g.node.peers.value.takeIf { it.size == 1 && g.node.isGateway.value } }
        val env = lost(Identity.generate(TestCrypto))
        g.node.originate(env)
        eventually(what = "upload") { server.uploaded.firstOrNull { it.msgId == env.msgId } }
        val atC = eventually(what = "C shows it") { seenC.firstOrNull { it.msgId == env.msgId } }
        assertEquals(listOf("G", "C"), atC.trace)
    }

    @Test
    fun clientBroadcastArrivesParsedAndItsReceiptReachesTheSender() = runBlocking<Unit> {
        val serverSign = TestCrypto.newSignKeyPair()
        val keys = ServerKeys(serverSign.publicKey, TestCrypto.newBoxKeyPair().publicKey)
        val uplink = FakeUplink(online = true)
        val a = phone("A"); val b = phone("B"); val g = phone("G", uplink)
        val ravi = Identity.generate(TestCrypto)
        val sender = HopnetClient(a.node, TestCrypto, ravi, keys, scope).also { it.start() }
        val helper = HopnetClient(b.node, TestCrypto, Identity.generate(TestCrypto), keys, scope).also { it.start() }
        g.node.setIdentity(null, serverSign.publicKey, TestCrypto)
        val got = CopyOnWriteArrayList<IncomingBroadcast>()
        val updates = CopyOnWriteArrayList<OutgoingUpdate>()
        scope.launch { helper.broadcasts.collect { got += it } }
        scope.launch { sender.updates.collect { updates += it } }
        a linkTo b; b linkTo g
        eventually(what = "links") { b.node.peers.value.takeIf { it.size == 2 && g.node.isGateway.value } }

        val data = JsonObject(mapOf("case" to JsonPrimitive("abcd1234"), "lat" to JsonPrimitive(9.4346)))
        val first = sender.broadcast("findme", "lost", data)
        val second = sender.broadcast("findme", "found", data) // same millisecond is fine: timestamps strictly increase
        val atB = eventually(what = "B gets the update") { got.firstOrNull { it.msgId == second } }
        assertEquals(ravi.address, atB.from)
        assertEquals("findme", atB.service)
        assertEquals("found", atB.kind)
        assertEquals(data, atB.data)
        assertEquals(listOf("A", "B"), atB.path)
        assertTrue(got.none { it.msgId == first } || got.first { it.msgId == first }.sentAt < atB.sentAt)

        // The gateway uploaded the first one too; its receipt still counts as ours although the update replaced it.
        val upFirst = eventually(what = "first uploaded") { uplink.uploaded.firstOrNull { it.msgId == first } }
        uplink.push(Receipts.build(first, ReceiptStatus.RECEIVED, upFirst.hops, upFirst.trace, serverSign.secretKey, TestCrypto, System.currentTimeMillis()))
        val atServer = eventually(what = "sender hears it reached the server") {
            updates.filterIsInstance<OutgoingUpdate.AtServer>().firstOrNull { it.msgId == first }
        }
        assertEquals(listOf("A", "B", "G"), atServer.path)
    }

    @Test
    fun aClockSetBackAfterARestartStillProducesNewerUpdates() = runBlocking<Unit> {
        val keys = ServerKeys(TestCrypto.newSignKeyPair().publicKey, TestCrypto.newBoxKeyPair().publicKey)
        val a = phone("A"); val b = phone("B")
        val ravi = Identity.generate(TestCrypto)
        val seenB = b.shown()
        a linkTo b
        eventually(what = "link") { b.node.peers.value.takeIf { it.size == 1 } }
        val data = JsonObject(mapOf("case" to JsonPrimitive("abcd1234")))
        val t = System.currentTimeMillis()

        val before = HopnetClient(a.node, TestCrypto, ravi, keys, scope, clock = { t + 600_000 }) // clock 10 min fast
        val lost = before.broadcast("findme", "lost", data)
        eventually(what = "B shows lost") { seenB.firstOrNull { it.msgId == lost } }
        before.close()

        // The app restarts after the network set the clock back 10 minutes.
        val after = HopnetClient(a.node, TestCrypto, ravi, keys, scope, clock = { t })
        val found = after.broadcast("findme", "found", data)
        val shown = eventually(what = "B shows found") { seenB.firstOrNull { it.msgId == found } }
        assertTrue(shown.createdAt > seenB.first { it.msgId == lost }.createdAt)
        assertEquals(listOf(found), b.heldBroadcasts())
    }

    @Test
    fun broadcastsAreNotEvictedByTheStoreCap() = runBlocking<Unit> {
        val a = phone("A"); val b = phone("B")
        a linkTo b
        eventually(what = "link") { b.node.peers.value.takeIf { it.size == 1 } }
        val env = lost(Identity.generate(TestCrypto))
        a.node.originate(env)
        eventually(what = "B carries it") { b.heldBroadcasts().takeIf { it.isNotEmpty() } }
        b.store.housekeeping(System.currentTimeMillis(), maxEnvelopes = 0, seenTtlMs = Router.SEEN_TTL_MS)
        assertEquals(listOf(env.msgId), b.heldBroadcasts())
    }
}
