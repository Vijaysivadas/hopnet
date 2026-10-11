package app.hopnet.core.protocol

import app.hopnet.core.crypto.Identity
import app.hopnet.core.testing.TestCrypto
import app.hopnet.core.testing.upEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun envelopeRoundTrip() {
        val env = upEnvelope().stamped("Asha phone").hopped("B").hopped("G")
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(env))
        assertEquals(env.msgId, decoded.msgId)
        assertEquals(EnvelopeType.UP, decoded.type)
        assertEquals(2, decoded.hops)
        assertEquals(listOf("Asha-phone", "B", "G"), decoded.trace)
        assertEquals(env.expiresAt, decoded.expiresAt)
        assertArrayEquals(env.body, decoded.body)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(EnvelopeCodec.decodeOrNull(ByteArray(10)))
        val bytes = EnvelopeCodec.encode(upEnvelope())
        assertNull(EnvelopeCodec.decodeOrNull(bytes.copyOf(bytes.size - 5)))
        bytes[0] = 0x00
        assertNull(EnvelopeCodec.decodeOrNull(bytes))
    }

    /** Bytes produced by server/hopnet_server/envelope.py (see server/tests/test_envelope.py). */
    @Test
    fun decodesServerVector() {
        val hex = "48010201" + "00112233445566778899aabbccddeeff" + "ffeeddccbbaa99887766554433221100" +
            "030c" + "0000018c0b6a0000" + "0000018c0b78a6a0" + "0003" + "010203" + "02" + "05636c6f7564" + "0147"
        val env = EnvelopeCodec.decode(Hex.decode(hex))
        assertEquals(EnvelopeType.DOWN, env.type)
        assertEquals("00112233445566778899aabbccddeeff", env.msgId)
        assertEquals("ffeeddccbbaa99887766554433221100", env.dest)
        assertEquals(3, env.hops)
        assertEquals(12, env.maxHops)
        assertEquals(0x0000018c0b6a0000L, env.createdAt)
        assertEquals(0x0000018c0b78a6a0L, env.expiresAt)
        assertArrayEquals(byteArrayOf(1, 2, 3), env.body)
        assertEquals(listOf("cloud", "G"), env.trace)
        assertEquals(hex, Hex.encode(EnvelopeCodec.encode(env)))
    }

    @Test
    fun sealedPayloadVerifiesSignerAndRejectsTampering() {
        val sender = Identity.generate(TestCrypto)
        val server = Identity.generate(TestCrypto)
        val json = """{"hello":"mesh"}""".toByteArray()
        val body = SealedPayload.seal(json, sender.sign.secretKey, server.box.publicKey, TestCrypto)
        assertEquals(0, (body.size - SealedPayload.SEAL_OVERHEAD) % SealedPayload.PAD_TO)

        val opened = SealedPayload.openAndVerify(body, server.box.publicKey, server.box.secretKey, sender.sign.publicKey, TestCrypto)
        assertArrayEquals(json, opened)

        val stranger = Identity.generate(TestCrypto)
        assertNull(SealedPayload.openAndVerify(body, server.box.publicKey, server.box.secretKey, stranger.sign.publicKey, TestCrypto))
        assertNull(SealedPayload.openAndVerify(body, stranger.box.publicKey, stranger.box.secretKey, sender.sign.publicKey, TestCrypto))
        val tampered = body.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        assertNull(SealedPayload.openAndVerify(tampered, server.box.publicKey, server.box.secretKey, sender.sign.publicKey, TestCrypto))
    }

    @Test
    fun receiptsAreSignedAndBoundToTheirId() {
        val server = TestCrypto.newSignKeyPair()
        val target = Ids.newMsgId()
        val env = Receipts.build(target, ReceiptStatus.RECEIVED, 3, listOf("A", "B", "G"), server.secretKey, TestCrypto, 1_000)
        val receipt = Receipts.parseAndVerify(env, server.publicKey, TestCrypto)
        assertNotNull(receipt)
        assertEquals(target, receipt!!.targetId)
        assertEquals(3, receipt.hops)
        assertEquals(listOf("A", "B", "G"), receipt.path)
        assertEquals(Receipts.receiptMsgId(target, ReceiptStatus.RECEIVED), env.msgId)

        val other = TestCrypto.newSignKeyPair()
        assertNull(Receipts.parseAndVerify(env, other.publicKey, TestCrypto))
        val renamed = Envelope(env.type, env.flags, Ids.newMsgId(), env.dest, 0, 12, env.createdAt, env.expiresAt, env.body)
        assertNull(Receipts.parseAndVerify(renamed, server.publicKey, TestCrypto))
    }

    @Test
    fun broadcastsVerifyTheirSenderAndRejectTampering() {
        val ravi = Identity.generate(TestCrypto)
        val inner = BroadcastInner(
            id = Ids.newMsgId(), from = ravi.address, ts = 1_000, exp = 61_000, svc = "findme", kind = "lost",
            data = JsonObject(mapOf("lat" to JsonPrimitive(9.4346))),
        )
        val env = Broadcasts.build(inner, ravi.sign, TestCrypto)
        assertEquals(EnvelopeType.BROADCAST, env.type)
        assertEquals(ravi.address, env.dest)
        assertEquals(inner, Broadcasts.open(EnvelopeCodec.decode(EnvelopeCodec.encode(env.hopped("B"))), TestCrypto))

        fun with(dest: String = env.dest, createdAt: Long = env.createdAt, expiresAt: Long = env.expiresAt, msgId: String = env.msgId, body: ByteArray = env.body) =
            Envelope(EnvelopeType.BROADCAST, env.flags, msgId, dest, 0, env.maxHops, createdAt, expiresAt, body)
        assertNull(Broadcasts.open(with(dest = Identity.generate(TestCrypto).address), TestCrypto))
        assertNull(Broadcasts.open(with(createdAt = 2_000), TestCrypto))
        assertNull(Broadcasts.open(with(expiresAt = 99_000), TestCrypto))
        assertNull(Broadcasts.open(with(msgId = Ids.newMsgId()), TestCrypto))
        assertNull(Broadcasts.open(with(body = env.body.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }), TestCrypto))
        assertNull(Broadcasts.open(with(body = env.body + 0), TestCrypto))

        // Someone else can sign a broadcast, but only from their own address.
        val attacker = Identity.generate(TestCrypto)
        assertThrows(IllegalArgumentException::class.java) { Broadcasts.build(inner, attacker.sign, TestCrypto) }
    }

    /** Bytes produced by server/hopnet_server/crypto.py (see server/tests/test_findme.py). */
    @Test
    fun opensServerBroadcastVector() {
        val hex = "4801040100112233445566778899aabbccddeeff56475aa75463474c0285df5dbf2bcab701100000018bcfe568000000018bcfe6526001" +
            "3503a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b87ce06d8c4b686e3600255bae185a83c07276010399b9" +
            "3b0da57fecffa8980709afb6849f5f961be60a70595be9890e375521c9f3038747fe2acc916c7339ec0800d37b2276223a312c22696422" +
            "3a223030313132323333343435353636373738383939616162626363646465656666222c2266726f6d223a2235363437356161373534" +
            "36333437346330323835646635646266326263616237222c227473223a313730303030303030303030302c22657870223a3137303030" +
            "30303036303030302c22737663223a2266696e646d65222c226b696e64223a226c6f7374222c2264617461223a7b2263617365223a22" +
            "6131623263336434222c226c6174223a392e343334362c226c6f6e223a37372e303831377d7d0201520142"
        val env = EnvelopeCodec.decode(Hex.decode(hex))
        assertEquals(EnvelopeType.BROADCAST, env.type)
        assertEquals(listOf("R", "B"), env.trace)
        val inner = Broadcasts.open(env, TestCrypto)
        assertNotNull(inner)
        assertEquals("56475aa75463474c0285df5dbf2bcab7", inner!!.from)
        assertEquals("findme", inner.svc)
        assertEquals("lost", inner.kind)
        assertEquals("a1b2c3d4", inner.data["case"]?.jsonPrimitive?.content)
        assertEquals(hex, Hex.encode(EnvelopeCodec.encode(env)))
    }

    @Test
    fun framesRoundTrip() {
        val hello = FrameCodec.decode(FrameCodec.encode(Frame.Hello("abc123", "Ravi's phone", true)))
        assertEquals(Frame.Hello("abc123", "Ravi's-phone", true), hello)
        val env = upEnvelope()
        val frame = FrameCodec.decode(FrameCodec.encode(Frame.Env(env)))
        assertTrue(frame is Frame.Env && frame.envelope.msgId == env.msgId)
    }
}
