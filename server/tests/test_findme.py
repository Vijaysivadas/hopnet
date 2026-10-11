"""Find Me: signed broadcasts from lost people reach the server through any gateway."""

import json
import time

import pytest
from fastapi.testclient import TestClient
from nacl.signing import SigningKey

from hopnet_server import envelope as wire
from hopnet_server.app import create_app
from hopnet_server.crypto import RECEIVED, REJECTED, build_broadcast_body, open_broadcast

from .phone import Phone, parse_receipt, receive_until, send_up

CASE = "a1b2c3d4e5f60718"


@pytest.fixture()
def client(tmp_path):
    with TestClient(create_app(tmp_path)) as c:
        yield c


def hello(ws, label, addr=None):
    ws.send_text(json.dumps({"t": "hello", "node": label.lower(), "label": label, "addr": addr}))


def receipt_for(ws, msg_id):
    return parse_receipt(receive_until(ws, lambda e: e.type == wire.RECEIPT and e.body[:16].hex() == msg_id))


def cases(client):
    return client.get("/v1/findme/cases").json()


def test_broadcast_body_round_trip():
    key = SigningKey.generate()
    body = build_broadcast_body(b'{"x":1}', key)
    assert open_broadcast(body) == (bytes(key.verify_key), b'{"x":1}')
    tampered = bytearray(body)
    tampered[-2] ^= 1
    assert open_broadcast(bytes(tampered)) is None
    assert open_broadcast(body + b"x") is None
    assert open_broadcast(body[:50]) is None


def test_lost_then_found_reaches_the_police_page(client):
    ravi = Phone("ravi", "Ravi")
    ravi.register(client)
    now = int(time.time() * 1000)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")

        # Ravi is lost; his alert hopped R > B > G before G had internet.
        lost_id, lost = ravi.broadcast("findme", "lost", {
            "case": CASE, "name": "Someone else", "lat": 9.43461, "lon": 77.08172, "acc": 12.5,
            "fixAt": now - 5000, "note": "near the blue tent", "battery": 41,
        }, trace=["R", "B", "G"], hops=2, ts=now)
        send_up(gw, lost)
        assert receipt_for(gw, lost_id) == (lost_id, RECEIVED, 2, "R>B>G")

        # The same broadcast through another gateway: same receipt, counted once.
        send_up(gw, lost)
        assert receipt_for(gw, lost_id)[1] == RECEIVED

        [case] = cases(client)
        assert case["status"] == "lost" and case["caseId"] == CASE
        assert case["name"] == "Ravi" and case["handle"] == "ravi" and case["verified"]  # registered name wins
        assert (case["lat"], case["lon"], case["acc"]) == (9.43461, 77.08172, 12.5)
        assert case["note"] == "near the blue tent" and case["battery"] == 41
        assert (case["path"], case["hops"], case["gateway"], case["updates"]) == ("R>B>G", 2, "G", 1)

        # An update with no GPS fix keeps the last location.
        upd_id, upd = ravi.broadcast("findme", "lost", {"case": CASE, "battery": 39}, ts=now + 60_000)
        send_up(gw, upd)
        assert receipt_for(gw, upd_id)[1] == RECEIVED
        [case] = cases(client)
        assert (case["lat"], case["lon"], case["battery"], case["updates"]) == (9.43461, 77.08172, 39, 2)
        assert case["note"] == "near the blue tent"

        # Found.
        found_id, found = ravi.broadcast("findme", "found", {"case": CASE, "lat": 9.4350, "lon": 77.0820, "acc": 8},
                                         ts=now + 120_000)
        send_up(gw, found)
        assert receipt_for(gw, found_id)[1] == RECEIVED
        [case] = cases(client)
        assert case["status"] == "found" and (case["lat"], case["lon"]) == (9.4350, 77.0820)

        # A stale "lost" copy that arrives late can't reopen the case.
        old_id, old = ravi.broadcast("findme", "lost", {"case": CASE, "lat": 1.0, "lon": 1.0}, ts=now + 30_000)
        send_up(gw, old)
        assert receipt_for(gw, old_id)[1] == RECEIVED
        [case] = cases(client)
        assert case["status"] == "found" and case["lat"] == 9.4350 and case["updates"] == 4

    state = client.get("/v1/debug/state").json()
    assert any(t["svc"] == "findme" and t["kind"] == "lost" and t["path"] == "R>B>G" for t in state["traffic"])
    page = client.get("/").text
    assert "Find Me" in page and "Ravi" in page
    police = client.get("/police").text
    assert "/v1/findme/cases" in police and "textContent" in police


def test_unregistered_phone_is_shown_as_unverified(client):
    stranger = Phone("nobody", "Asha")  # never registered
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        msg_id, raw = stranger.broadcast("findme", "lost", {"case": CASE, "name": "Asha", "lat": 9.4, "lon": 77.1})
        send_up(gw, raw)
        assert receipt_for(gw, msg_id)[1] == RECEIVED
    [case] = cases(client)
    assert case["name"] == "Asha" and case["handle"] is None and not case["verified"]


def test_forged_tampered_and_malformed_broadcasts(client):
    ravi = Phone("ravi", "Ravi")
    ravi.register(client)
    attacker = SigningKey.generate()
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "G")
        data = {"case": CASE, "lat": 9.4, "lon": 77.1}

        # Someone else signs a "found" in Ravi's name.
        forged_id, forged = ravi.broadcast("findme", "found", data, signer=attacker)
        send_up(gw, forged)
        assert receipt_for(gw, forged_id)[1] == REJECTED

        # Signed by Ravi but the header says another sender.
        moved_id, moved = ravi.broadcast("findme", "lost", data, dest=bytes(16))
        send_up(gw, moved)
        assert receipt_for(gw, moved_id)[1] == REJECTED

        # Inner timestamp doesn't match the header.
        skew_id, skew = ravi.broadcast("findme", "lost", data, tamper={"ts": 1})
        send_up(gw, skew)
        assert receipt_for(gw, skew_id)[1] == REJECTED

        # Chat doesn't accept broadcasts.
        chat_id, chat = ravi.broadcast("chat", "message", {"to": "ravi", "text": "hi"})
        send_up(gw, chat)
        assert receipt_for(gw, chat_id)[1] == REJECTED

        # Accepted, but not a valid Find Me report: no case is created.
        bad_id, bad = ravi.broadcast("findme", "lost", {"case": "not hex!", "lat": "north"})
        send_up(gw, bad)
        assert receipt_for(gw, bad_id)[1] == RECEIVED

        # Out-of-range coordinates are dropped, the report still counts.
        odd_id, odd = ravi.broadcast("findme", "lost", {"case": CASE, "lat": 123.0, "lon": 77.1, "battery": 400})
        send_up(gw, odd)
        assert receipt_for(gw, odd_id)[1] == RECEIVED
    [case] = cases(client)
    assert case["lat"] is None and case["lon"] is None and case["battery"] is None


# The same bytes are opened by core/src/test/.../ProtocolTest.opensServerBroadcastVector (Kotlin).
BROADCAST_VECTOR_HEX = (
    "4801040100112233445566778899aabbccddeeff56475aa75463474c0285df5dbf2bcab701100000018bcfe568000000018bcfe6526001"
    "3503a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b87ce06d8c4b686e3600255bae185a83c07276010399b9"
    "3b0da57fecffa8980709afb6849f5f961be60a70595be9890e375521c9f3038747fe2acc916c7339ec0800d37b2276223a312c22696422"
    "3a223030313132323333343435353636373738383939616162626363646465656666222c2266726f6d223a2235363437356161373534"
    "36333437346330323835646635646266326263616237222c227473223a313730303030303030303030302c22657870223a3137303030"
    "30303036303030302c22737663223a2266696e646d65222c226b696e64223a226c6f7374222c2264617461223a7b2263617365223a22"
    "6131623263336434222c226c6174223a392e343334362c226c6f6e223a37372e303831377d7d0201520142"
)


def test_broadcast_vector_matches_kotlin():
    key = SigningKey(bytes(range(32)))
    address = "56475aa75463474c0285df5dbf2bcab7"
    js = ('{"v":1,"id":"00112233445566778899aabbccddeeff","from":"%s","ts":1700000000000,"exp":1700000060000,'
          '"svc":"findme","kind":"lost","data":{"case":"a1b2c3d4","lat":9.4346,"lon":77.0817}}' % address).encode()
    env = wire.Envelope(wire.BROADCAST, wire.FLAG_TRACE, bytes.fromhex("00112233445566778899aabbccddeeff"),
                        bytes.fromhex(address), 1, 16, 1700000000000, 1700000060000, build_broadcast_body(js, key),
                        ["R", "B"])
    assert wire.encode(env).hex() == BROADCAST_VECTOR_HEX
    assert open_broadcast(wire.decode(bytes.fromhex(BROADCAST_VECTOR_HEX)).body) == (bytes(key.verify_key), js)
