"""FastAPI application: registration, handle lookup, the gateway WebSocket, a debug page and the
Find Me police page. Services with background work (Live Info watches) start and stop with the app."""

from __future__ import annotations

import json
import logging
import os
import re
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from .crypto import ServerKeys, address_of, b64, unb64, verify
from .db import Database, User
from .debug_page import render_debug_page
from .hub import Hub, now_ms
from .police_page import POLICE_PAGE
from .services import default_services

log = logging.getLogger("hopnet")

HANDLE_RE = re.compile(r"^[a-z0-9_]{3,20}$")
DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent / "data"


class RegisterRequest(BaseModel):
    handle: str
    name: str
    signPk: str
    encPk: str
    sig: str


def create_app(data_dir: Path | None = None) -> FastAPI:
    data_dir = Path(data_dir or os.environ.get("HOPNET_DATA") or DEFAULT_DATA_DIR)
    keys = ServerKeys.load_or_create(data_dir / "server_keys.json")
    db = Database(data_dir / "hopnet.db")
    hub = Hub(db, keys, default_services())

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        for service in hub.services.values():
            await service.start(hub)
        try:
            yield
        finally:
            for service in hub.services.values():
                await service.stop()

    app = FastAPI(title="Hopnet server", version="0.1.0", lifespan=lifespan)
    app.state.hub = hub

    def server_info() -> dict:
        return {"name": "hopnet", "signPk": b64(keys.sign_pk), "encPk": b64(keys.enc_pk)}

    @app.get("/v1/info")
    def info():
        return server_info()

    @app.post("/v1/register")
    def register(req: RegisterRequest):
        handle = req.handle.strip().lstrip("@").lower()
        name = req.name.strip()[:40]
        if not HANDLE_RE.match(handle):
            raise HTTPException(400, "Handle must be 3-20 characters: a-z, 0-9 or _")
        if not name:
            raise HTTPException(400, "Name is required")
        try:
            sign_pk, enc_pk, sig = unb64(req.signPk), unb64(req.encPk), unb64(req.sig)
        except ValueError:
            raise HTTPException(400, "Keys must be base64")
        if len(sign_pk) != 32 or len(enc_pk) != 32:
            raise HTTPException(400, "Keys must be 32 bytes")
        if not verify(sig, f"HNREG1|{req.handle}|{req.signPk}|{req.encPk}".encode(), sign_pk):
            raise HTTPException(400, "Signature does not match the key")
        existing = db.user_by_handle(handle)
        if existing is not None and existing.sign_pk != sign_pk:
            raise HTTPException(409, f"@{handle} is already taken")
        user = User(handle, name, sign_pk, enc_pk, address_of(sign_pk))
        db.upsert_user(user, now_ms())
        db.event("user", f"registered @{handle} ({name})", now_ms())
        return {"address": user.address, "handle": handle, "name": name, "server": server_info()}

    @app.get("/v1/users/{handle}")
    def lookup(handle: str):
        user = db.user_by_handle(handle.strip().lstrip("@").lower())
        if user is None:
            raise HTTPException(404, "No such user")
        return {"handle": user.handle, "name": user.name}

    @app.websocket("/v1/gateway/ws")
    async def gateway(ws: WebSocket):
        await ws.accept()
        session = hub.open_session(ws)
        try:
            while True:
                text = await ws.receive_text()
                try:
                    msg = json.loads(text)
                except ValueError:
                    continue
                if isinstance(msg, dict):
                    await hub.on_message(session, msg)
        except WebSocketDisconnect:
            pass
        finally:
            hub.close_session(session)

    @app.get("/v1/debug/state")
    def debug_state():
        return hub.snapshot()

    @app.get("/", response_class=HTMLResponse)
    def debug_page():
        return render_debug_page(hub.snapshot())

    @app.get("/v1/findme/cases")
    def findme_cases():
        return hub.findme_cases()

    @app.get("/police", response_class=HTMLResponse)
    def police_page():
        return POLICE_PAGE

    return app
