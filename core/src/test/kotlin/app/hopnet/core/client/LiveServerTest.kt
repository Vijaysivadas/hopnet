package app.hopnet.core.client

import app.hopnet.core.crypto.Identity
import app.hopnet.core.crypto.ServerKeys
import app.hopnet.core.gateway.GatewayClient
import app.hopnet.core.gateway.GatewayHello
import app.hopnet.core.protocol.B64
import app.hopnet.core.protocol.Ids
import app.hopnet.core.testing.TestCrypto
import app.hopnet.core.testing.TestPhone
import app.hopnet.core.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole system minus the radios: real Router/MeshNode/HopnetClient, real libsodium, real
 * WebSockets, real Python server. Mesh 1 is A–B–G, mesh 2 is R–G2; only G and G2 are online.
 *
 *   cd server && .venv/Scripts/python -m uvicorn hopnet_server.main:app --port 8000
 *   HOPNET_LIVE_SERVER=http://127.0.0.1:8000 ./gradlew :core:test --tests '*LiveServerTest*'
 */
class LiveServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun ashaMessagesRaviAcrossTwoMeshes() = runBlocking<Unit> {
        val base = System.getenv("HOPNET_LIVE_SERVER").orEmpty()
        assumeTrue("set HOPNET_LIVE_SERVER to run", base.isNotBlank())

        val api = ServerApi()
        val tag = Ids.newNodeId().take(6)
        val asha = Identity.generate(TestCrypto)
        val ravi = Identity.generate(TestCrypto)
        val reg = api.register(base, "asha_$tag", "Asha", asha, TestCrypto)
        api.register(base, "ravi_$tag", "Ravi", ravi, TestCrypto)
        val server = ServerKeys(B64.decode(reg.server.signPk), B64.decode(reg.server.encPk))
        assertEquals(asha.address, reg.address)

        fun gateway(label: String) = GatewayClient(scope).apply {
            serverUrl = base
            hello = GatewayHello(node = label, label = label)
            setEnabled(true)
        }
        val a = TestPhone("A", scope); val b = TestPhone("B", scope); val g = TestPhone("G", scope, gateway("G"))
        val r = TestPhone("R", scope); val g2 = TestPhone("G2", scope, gateway("G2"))
        listOf(b, g, g2).forEach { it.node.setIdentity(null, server.signPk, TestCrypto) } // relays verify receipts

        val fast = HopnetClient.Config(pollIntervalMs = 2_000, pollOnNewLinkAfterMs = 500)
        val ashaClient = HopnetClient(a.node, TestCrypto, asha, server, scope, fast).also { it.start() }
        val raviClient = HopnetClient(r.node, TestCrypto, ravi, server, scope, fast).also { it.start() }
        val ashaUpdates = CopyOnWriteArrayList<OutgoingUpdate>()
        val ashaInbox = CopyOnWriteArrayList<IncomingMessage>()
        val raviInbox = CopyOnWriteArrayList<IncomingMessage>()
        scope.launch { ashaClient.updates.collect { ashaUpdates += it } }
        scope.launch { ashaClient.incoming.collect { ashaInbox += it } }
        scope.launch { raviClient.incoming.collect { raviInbox += it } }

        a linkTo b; b linkTo g; r linkTo g2
        eventually(10_000, "gateways online") { (g.node.isGateway.value && g2.node.isGateway.value).takeIf { it } }

        val text = "Namaste from the basement, $tag"
        val id = ashaClient.send("chat", "message", JsonObject(mapOf("to" to JsonPrimitive("ravi_$tag"), "text" to JsonPrimitive(text))))

        val atServer = eventually(10_000, "server receipt") {
            ashaUpdates.filterIsInstance<OutgoingUpdate.AtServer>().firstOrNull { it.msgId == id }
        }
        assertEquals(2, atServer.hops)
        assertEquals(listOf("A", "B", "G"), atServer.path)

        val got = eventually(15_000, "Ravi receives") { raviInbox.firstOrNull { it.service == "chat" && it.kind == "message" } }
        assertEquals(text, got.data["text"]?.jsonPrimitive?.content)
        assertEquals("asha_$tag", got.data["from"]?.jsonPrimitive?.content)
        assertEquals(listOf("cloud", "G2", "R"), got.path)

        val delivered = eventually(15_000, "delivered status") {
            ashaInbox.firstOrNull { it.kind == "status" && it.data["ref"]?.jsonPrimitive?.content == id }
        }
        assertEquals("delivered", delivered.data["status"]?.jsonPrimitive?.content)
        assertEquals(listOf("cloud", "G", "B", "A"), delivered.path)

        // The server page shows the whole journey: up A>B>G, pushed via G2, down cloud>G2>R (reported by Ravi's ack).
        val journey = eventually(10_000, "journey on server") {
            val body = OkHttpClient().newCall(Request.Builder().url("$base/v1/debug/state").build()).execute().use { it.body.string() }
            HopnetJson.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { it["id"]?.jsonPrimitive?.content == id && it["downPath"]?.jsonPrimitive?.contentOrNull != null }
        }
        assertEquals("A>B>G", journey["upPath"]?.jsonPrimitive?.content)
        assertEquals("cloud>G2>R", journey["downPath"]?.jsonPrimitive?.content)
        assertEquals("G2", journey["pushedVia"]?.jsonArray?.single()?.jsonPrimitive?.content)

        // Relays end up empty once receipts flow back (sender's UP and the DOWNs are purged).
        eventually(15_000, "B purged") { b.store.live(System.currentTimeMillis(), 50).none { it.envelope.type.name != "RECEIPT" }.takeIf { it } }

        // Unknown handle: the server tells the sender.
        val bad = ashaClient.send("chat", "message", JsonObject(mapOf("to" to JsonPrimitive("ghost_$tag"), "text" to JsonPrimitive("?"))))
        val failed = eventually(10_000, "failed status") {
            ashaInbox.firstOrNull { it.kind == "status" && it.data["ref"]?.jsonPrimitive?.content == bad }
        }
        assertEquals("failed", failed.data["status"]?.jsonPrimitive?.content)
        println("live test OK: ${atServer.path.joinToString(" > ")} → server → ${got.path.joinToString(" > ")}")
    }

    /** Ravi is lost: Asha's phone next to him shows it with no server, and the police page gets it via G. */
    @Test
    fun findMeReachesNearbyPhonesAndThePolice() = runBlocking<Unit> {
        val base = System.getenv("HOPNET_LIVE_SERVER").orEmpty()
        assumeTrue("set HOPNET_LIVE_SERVER to run", base.isNotBlank())

        val api = ServerApi()
        val tag = Ids.newNodeId().take(6)
        val ravi = Identity.generate(TestCrypto)
        val asha = Identity.generate(TestCrypto)
        val reg = api.register(base, "ravi_$tag", "Ravi", ravi, TestCrypto)
        api.register(base, "asha_$tag", "Asha", asha, TestCrypto)
        val server = ServerKeys(B64.decode(reg.server.signPk), B64.decode(reg.server.encPk))
        val uplink = GatewayClient(scope).apply {
            serverUrl = base
            hello = GatewayHello(node = "G", label = "G")
            setEnabled(true)
        }
        val r = TestPhone("R", scope); val b = TestPhone("B", scope); val g = TestPhone("G", scope, uplink)
        g.node.setIdentity(null, server.signPk, TestCrypto)
        val raviClient = HopnetClient(r.node, TestCrypto, ravi, server, scope).also { it.start() }
        val ashaClient = HopnetClient(b.node, TestCrypto, asha, server, scope).also { it.start() }
        val raviUpdates = CopyOnWriteArrayList<OutgoingUpdate>()
        val ashaSees = CopyOnWriteArrayList<IncomingBroadcast>()
        scope.launch { raviClient.updates.collect { raviUpdates += it } }
        scope.launch { ashaClient.broadcasts.collect { ashaSees += it } }

        r linkTo b; b linkTo g
        eventually(10_000, "gateway online") { g.node.isGateway.value.takeIf { it } }

        val case = Ids.newMsgId().take(16)
        fun report(lat: Double) = JsonObject(
            mapOf(
                "case" to JsonPrimitive(case), "name" to JsonPrimitive("Ravi"), "lat" to JsonPrimitive(lat),
                "lon" to JsonPrimitive(77.0817), "acc" to JsonPrimitive(12.0), "note" to JsonPrimitive("near the blue tent"),
            )
        )
        val lost = raviClient.broadcast("findme", "lost", report(9.4346))

        val atAsha = eventually(10_000, "Asha's phone shows it") { ashaSees.firstOrNull { it.msgId == lost } }
        assertEquals("lost", atAsha.kind)
        assertEquals(ravi.address, atAsha.from)
        assertEquals(listOf("R", "B"), atAsha.path)

        val atServer = eventually(10_000, "police receipt") {
            raviUpdates.filterIsInstance<OutgoingUpdate.AtServer>().firstOrNull { it.msgId == lost }
        }
        assertEquals(listOf("R", "B", "G"), atServer.path)

        fun policeCase() = OkHttpClient().newCall(Request.Builder().url("$base/v1/findme/cases").build()).execute()
            .use { HopnetJson.parseToJsonElement(it.body.string()).jsonArray.map { c -> c.jsonObject } }
            .firstOrNull { it["caseId"]?.jsonPrimitive?.content == case }
        val onPage = eventually(10_000, "case on the police page") { policeCase() }
        assertEquals("lost", onPage["status"]?.jsonPrimitive?.content)
        assertEquals("ravi_$tag", onPage["handle"]?.jsonPrimitive?.content)
        assertEquals("9.4346", onPage["lat"]?.jsonPrimitive?.content)
        assertEquals("R>B>G", onPage["path"]?.jsonPrimitive?.content)
        assertEquals("near the blue tent", onPage["note"]?.jsonPrimitive?.content)

        val found = raviClient.broadcast("findme", "found", report(9.4350))
        eventually(10_000, "Asha sees he's found") { ashaSees.firstOrNull { it.msgId == found && it.kind == "found" } }
        eventually(10_000, "police page says found") { policeCase()?.takeIf { it["status"]?.jsonPrimitive?.content == "found" } }
        // Everyone keeps only the newest: the found update.
        for (p in listOf(r, b, g)) {
            eventually(5_000, "${p.name} holds only the update") {
                p.store.live(System.currentTimeMillis(), 50).filter { it.envelope.type.name == "BROADCAST" }
                    .map { it.envelope.msgId }.takeIf { it == listOf(found) }
            }
        }
        println("Find Me live test OK: ${atServer.path.joinToString(" > ")} → police page")
    }
}
