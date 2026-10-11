"""Service plug-in interface. Each Hopnet service (chat, food, tickets, SOS...) is one handler.

A handler receives verified requests from users (whatever path they took through the mesh)
and replies with `ctx.send_down(...)`. Transport, receipts, retries and delivery tracking are
handled by the hub; a handler only implements the service itself.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..db import MailItem, User
    from ..envelope import Envelope
    from ..hub import Hub, ServiceContext


class ServiceHandler:
    #: Matches the `svc` field apps send, e.g. Hopnet.send(service = "chat", ...)
    id: str = ""
    #: Whether phones may reach this service with signed, unencrypted BROADCAST envelopes.
    accepts_broadcasts: bool = False

    async def start(self, hub: "Hub") -> None:
        """Called once when the server starts, inside its event loop (e.g. to run background jobs)."""

    async def stop(self) -> None:
        """Called once when the server shuts down."""

    async def handle(self, ctx: "ServiceContext", sender: "User", request: dict) -> None:
        """`request` is the verified inner payload: {id, from, ts, exp, svc, kind, data}."""
        raise NotImplementedError

    async def on_delivered(self, ctx: "ServiceContext", item: "MailItem") -> None:
        """Called once when the recipient acknowledges a DOWN message this service sent."""

    async def handle_broadcast(self, ctx: "ServiceContext", address: str, sender: "User | None",
                               request: dict, envelope: "Envelope") -> None:
        """A verified BROADCAST a gateway uploaded (only if `accepts_broadcasts`).
        `request` is {id, from, ts, exp, svc, kind, data}; `sender` is None for an unregistered address."""
