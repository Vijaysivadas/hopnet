from __future__ import annotations

from .base import ServiceHandler

MAX_TEXT = 1000


class ChatService(ServiceHandler):
    """Messages: deliver a text to another Hopnet user by handle.

    up   chat/message {to, text}
    down chat/message {from, fromName, text, sentAt, ref}    to the recipient
    down chat/status  {ref, status: delivered|failed, reason} to the sender
    """

    id = "chat"

    async def handle(self, ctx, sender, request):
        if request.get("kind") != "message":
            return
        data = request.get("data", {})
        to = str(data.get("to", "")).strip().lstrip("@").lower()
        text = str(data.get("text", ""))[:MAX_TEXT]
        recipient = ctx.user_by_handle(to)
        ctx.annotate(summary=text[:120], peer=to, note=None if recipient else "no such user")
        if recipient is None:
            await ctx.send_down(sender.address, "chat", "status",
                                {"ref": request["id"], "status": "failed", "reason": f"no user @{to}", "at": ctx.now()})
            ctx.event("chat", f"✗ @{sender.handle} → @{to}: unknown user")
            return
        await ctx.send_down(
            recipient.address, "chat", "message",
            {"from": sender.handle, "fromName": sender.name, "text": text, "sentAt": request.get("ts"), "ref": request["id"]},
            ref=request["id"], origin=sender.address,
        )
        ctx.event("chat", f"✉ @{sender.handle} → @{recipient.handle}: “{text[:60]}”")

    async def on_delivered(self, ctx, item):
        if item.kind != "message" or not item.origin:
            return
        await ctx.send_down(item.origin, "chat", "status", {"ref": item.ref, "status": "delivered", "at": ctx.now()})
        recipient = ctx.user_by_address(item.recipient)
        ctx.event("chat", f"✓✓ delivered to @{recipient.handle if recipient else '?'} ({item.ref[:6] if item.ref else ''})")
