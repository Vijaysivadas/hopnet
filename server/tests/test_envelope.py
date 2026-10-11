import pytest
from nacl.public import PrivateKey
from nacl.signing import SigningKey

from hopnet_server import envelope as wire
from hopnet_server.crypto import RECEIVED, build_receipt, open_payload, receipt_msg_id, seal_payload, verify

# The same bytes are decoded by core/src/test/.../ProtocolTest.decodesServerVector (Kotlin).
VECTOR_HEX = (
    "48010201" + "00112233445566778899aabbccddeeff" + "ffeeddccbbaa99887766554433221100"
    + "030c" + "0000018c0b6a0000" + "0000018c0b78a6a0" + "0003" + "010203" + "02" + "05636c6f7564" + "0147"
)


def test_vector_matches_kotlin():
    env = wire.Envelope(
        type=wire.DOWN, flags=wire.FLAG_TRACE,
        msg_id=bytes.fromhex("00112233445566778899aabbccddeeff"),
        dest=bytes.fromhex("ffeeddccbbaa99887766554433221100"),
        hops=3, max_hops=12, created_at=0x18C0B6A0000, expires_at=0x18C0B78A6A0,
        body=b"\x01\x02\x03", trace=["cloud", "G"],
    )
    assert wire.encode(env).hex() == VECTOR_HEX
    assert wire.decode(bytes.fromhex(VECTOR_HEX)) == env


def test_rejects_garbage():
    with pytest.raises(wire.ProtocolError):
        wire.decode(b"\x48\x01")
    raw = bytearray.fromhex(VECTOR_HEX)
    raw[0] = 0
    with pytest.raises(wire.ProtocolError):
        wire.decode(bytes(raw))


def test_sealed_payload_round_trip():
    sender, server = SigningKey.generate(), PrivateKey.generate()
    body = seal_payload(b'{"x":1}', sender, bytes(server.public_key))
    assert (len(body) - 48) % 256 == 0
    sig, js = open_payload(body, server)
    assert js == b'{"x":1}'
    assert verify(sig, js, bytes(sender.verify_key))
    assert open_payload(body, PrivateKey.generate()) is None


def test_receipt_id_is_deterministic():
    key = SigningKey.generate()
    target = bytes(range(16))
    r1 = build_receipt(target, RECEIVED, 2, ["A", "B"], key, 1)
    r2 = build_receipt(target, RECEIVED, 2, ["A", "B"], key, 999)
    assert r1.msg_id == r2.msg_id == receipt_msg_id(target, RECEIVED)
