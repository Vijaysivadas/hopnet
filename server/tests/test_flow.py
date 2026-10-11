"""End-to-end through the real app: two gateways, a sender and a recipient in different meshes."""

import json

import pytest
from fastapi.testclient import TestClient
from nacl.signing import SigningKey

from hopnet_server import envelope as wire
from hopnet_server.app import create_app
from hopnet_server.crypto import DELIVERED, RECEIVED, REJECTED

from .phone import Phone, parse_receipt, receive_envelopes, receive_until, send_up


@pytest.fixture()
def client(tmp_path):
    with TestClient(create_app(tmp_path)) as c:
        yield c


def hello(ws, label, addr=None):
    ws.send_text(json.dumps({"t": "hello", "node": label.lower(), "label": label, "addr": addr}))


def test_register_rules(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    assert client.get("/v1/users/asha").json() == {"handle": "asha", "name": "Asha"}
    assert client.get("/v1/users/nobody").status_code == 404
    thief = Phone("asha", "Not Asha")
    with pytest.raises(Exception):
        thief.register(client)  # handle belongs to another key
    bad = client.post("/v1/register", json={"handle": "x", "name": "X", "signPk": "", "encPk": "", "sig": ""})
    assert bad.status_code == 400


def test_message_across_two_meshes(client):
    asha, ravi = Phone("asha", "Asha"), Phone("ravi", "Ravi")
    asha.register(client)
    ravi.register(client)

    with client.websocket_connect("/v1/gateway/ws") as gw_a, client.websocket_connect("/v1/gateway/ws") as gw_r:
        hello(gw_a, "G")
        hello(gw_r, "G2")

        # Asha's message reached gateway G after hopping A > B > G.
        msg_id, raw = asha.up("chat", "message", {"to": "ravi", "text": "Namaste from the basement"},
                              trace=["A", "B", "G"], hops=2)
        send_up(gw_a, raw)
        receipt = receive_until(gw_a, lambda e: e.type == wire.RECEIPT)
        assert parse_receipt(receipt) == (msg_id, RECEIVED, 2, "A>B>G")

        # Same envelope again via another path: same receipt, no second message.
        send_up(gw_a, raw)
        again = receive_until(gw_a, lambda e: e.type == wire.RECEIPT)
        assert again.msg_id == receipt.msg_id

        # Ravi's phone polls through G2; the server pushes his pending mail there.
        poll_id, poll = ravi.up("sys", "poll", {}, trace=["R", "G2"], hops=1)
        send_up(gw_r, poll)
        down = receive_until(gw_r, lambda e: e.type == wire.DOWN)
        assert down.dest.hex() == ravi.address and down.trace == ["cloud"]
        inner = ravi.open_down(down)
        assert inner["svc"] == "chat" and inner["kind"] == "message"
        assert inner["data"]["text"] == "Namaste from the basement"
        assert inner["data"]["from"] == "asha" and inner["data"]["ref"] == msg_id
        assert asha.open_down(down) is None  # nobody else can read it

        # Ravi acks; G2 gets a DELIVERED receipt, Asha (reachable via G) gets "delivered".
        _, ack = ravi.up("sys", "ack", {"ids": [down.id_hex], "trips": [{"id": down.id_hex, "path": "cloud>G2>R", "hops": 1}]},
                         trace=["R", "G2"], hops=1)
        send_up(gw_r, ack)
        delivered = receive_until(gw_r, lambda e: e.type == wire.RECEIPT and parse_receipt(e)[1] == DELIVERED)
        assert parse_receipt(delivered)[0] == down.id_hex

        status = receive_until(gw_a, lambda e: e.type == wire.DOWN)
        inner = asha.open_down(status)
        assert inner["kind"] == "status" and inner["data"] == {**inner["data"], "ref": msg_id, "status": "delivered"}

    state = client.get("/v1/debug/state").json()
    assert any(m["delivered"] for m in state["mailbox"])
    journey = next(m for m in state["messages"] if m["id"] == msg_id)
    assert journey["from"] == "asha" and journey["to"] == "ravi" and journey["text"] == "Namaste from the basement"
    assert (journey["upPath"], journey["upHops"]) == ("A>B>G", 2)
    assert journey["pushedVia"] == ["G2"]
    assert (journey["downPath"], journey["downHops"]) == ("cloud>G2>R", 1)
    assert journey["deliveredAt"]
    kinds = {(t["svc"], t["kind"]) for t in state["traffic"]}
    assert {("chat", "message"), ("sys", "poll"), ("sys", "ack")} <= kinds
    page = client.get("/").text
    assert "Hopnet server" in page and "Namaste from the basement" in page and "☁ server" in page


def test_unknown_recipient_and_forgery(client):
    asha = Phone("asha", "Asha")
    asha.register(client)
    with client.websocket_connect("/v1/gateway/ws") as gw:
        hello(gw, "Asha-phone", addr=asha.address)  # Asha herself is online

        msg_id, raw = asha.up("chat", "message", {"to": "ghost", "text": "hello?"})
        send_up(gw, raw)
        assert parse_receipt(receive_until(gw, lambda e: e.type == wire.RECEIPT))[1] == RECEIVED
        status = asha.open_down(receive_until(gw, lambda e: e.type == wire.DOWN))
        assert status["data"]["status"] == "failed" and status["data"]["ref"] == msg_id
        journey = next(m for m in client.get("/v1/debug/state").json()["messages"] if m["id"] == msg_id)
        assert journey["note"] == "no such user" and journey["to"] == "ghost"

        forged_id, forged = asha.up("chat", "message", {"to": "asha", "text": "x"}, signer=SigningKey.generate())
        send_up(gw, forged)
        assert parse_receipt(receive_until(gw, lambda e: e.type == wire.RECEIPT)) [:2] == (forged_id, REJECTED)

        unknown_id, unknown = asha.up("teleport", "now", {})
        send_up(gw, unknown)
        assert parse_receipt(receive_until(gw, lambda e: e.type == wire.RECEIPT))[:2] == (unknown_id, REJECTED)
