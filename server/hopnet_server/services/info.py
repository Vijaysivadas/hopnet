from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from collections import deque

from ..info import LABELS, Card, Env, FetchError, Query, run_tool, survey
from ..info.ai import AI
from ..info.card import error_card, fit, size_of, worst
from ..info.env import Links, parse_query
from ..info.fetch import Fetcher
from ..info.geo import reverse
from ..info.text import fmt_bytes
from .base import ServiceHandler

log = logging.getLogger("hopnet.info")

ANSWER_TTL_MS = 12 * 60 * 60 * 1000      # a stale forecast is worse than none
WATCH_TTL_MS = 24 * 60 * 60 * 1000
TOOL_TIMEOUT_S = 70                       # public map servers can be slow; phones wait minutes anyway
RATE_LIMIT = (30, 10 * 60)                # requests per person per window (seconds)
WATCH_HOURS_DEFAULT, WATCH_HOURS_MAX = 72, 7 * 24


class InfoService(ServiceHandler):
    """Live Info: phones with no internet ask, the server reads the internet, a small card comes back.

    up   info/query   {tool, lat?, lon?, place?, q?, topic?, cat?, url?, key?, part?, lang?}
         tools: brief weather alerts nearby news wiki search ask read
    down info/answer  {ref, tool, ok, title, body?, items?[{t,s?,m?,k?,lvl?,lat?,lon?}], src?, url?, lvl?,
                       ai?, part?, parts?, key?, at, bytes, cached?, wire, watch?}
    up   info/watch   {topic:"alerts", lat, lon, hours?}  then new alerts for that spot arrive as info/answer
    up   info/unwatch {topic:"alerts"}
    down info/watch   {ref?, topic, on, until?, reason?}

    Answering happens in a background task: a slow website must never hold up the gateway's socket.
    """

    id = "info"

    def __init__(self, fetcher: Fetcher | None = None, ai: AI | None = None, watch_interval_s: float | None = None):
        self.fetcher = fetcher or Fetcher()
        self.ai = ai if ai is not None else AI.from_env()
        self.watch_interval_s = watch_interval_s or float(os.environ.get("HOPNET_INFO_WATCH_S", "300"))
        self.hub = None
        self._tasks: set[asyncio.Task] = set()
        self._recent: dict[str, deque] = {}
        self._watch_loop: asyncio.Task | None = None

    # ---- lifecycle -------------------------------------------------------------------------

    async def start(self, hub) -> None:
        self.hub = hub
        self._watch_loop = asyncio.create_task(self._watch_forever())

    async def stop(self) -> None:
        for task in [self._watch_loop, *self._tasks]:
            if task:
                task.cancel()
        await asyncio.gather(*[t for t in [self._watch_loop, *self._tasks] if t], return_exceptions=True)
        await self.fetcher.close()

    async def idle(self) -> None:
        """Waits until every answer in flight has been sent (tests)."""
        while self._tasks:
            await asyncio.gather(*list(self._tasks), return_exceptions=True)

    def _spawn(self, coro) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _env(self, hub) -> Env:
        return Env(fetcher=self.fetcher, links=Links(hub.db, hub.now), now=hub.now, ai=self.ai)

    # ---- requests --------------------------------------------------------------------------

    async def handle(self, ctx, sender, request):
        self.hub = self.hub or ctx.hub
        kind = request.get("kind")
        data = request.get("data") or {}
        ref = request["id"]
        if kind == "query":
            try:
                q = parse_query(data)
            except ValueError as exc:
                tool = str(data.get("tool") or "info")[:20]
                ctx.annotate(summary=f"{tool} · invalid", note=str(exc))
                ctx.hub.db.info_asked(ref, sender.address, tool, "invalid request", ctx.now())
                await self._send(ctx, sender, ref, error_card(tool, "Couldn't ask that", str(exc)), self._env(ctx.hub),
                                 started=time.monotonic())
                return
            ctx.annotate(summary=q.describe())
            ctx.hub.db.info_asked(ref, sender.address, q.tool, q.describe(), ctx.now())
            if not self._allow(sender.address):
                ctx.annotate(note="rate limited")
                card = error_card(q.tool, "Too many requests", "You've asked a lot in the last few minutes. Wait a little and try again.")
                await self._send(ctx, sender, ref, card, self._env(ctx.hub), started=time.monotonic())
                return
            self._spawn(self._answer(ctx, sender, ref, q))
        elif kind == "watch":
            self._spawn(self._start_watch(ctx, sender, ref, data))
        elif kind == "unwatch":
            topic = str(data.get("topic") or "alerts")
            removed = ctx.hub.db.info_watch_delete(sender.address, topic)
            ctx.annotate(summary=f"unwatch · {topic}", note=None if removed else "wasn't watching")
            await ctx.send_down(sender.address, "info", "watch", {"ref": ref, "topic": topic, "on": False}, ref=ref,
                                ttl_ms=WATCH_TTL_MS)
        else:
            ctx.annotate(note=f"unknown Live Info request {kind!r}")

    def _allow(self, address: str) -> bool:
        limit, window = RATE_LIMIT
        now = time.monotonic()
        recent = self._recent.setdefault(address, deque())
        while recent and recent[0] < now - window:
            recent.popleft()
        if len(recent) >= limit:
            return False
        recent.append(now)
        return True

    async def _answer(self, ctx, sender, ref: str, q: Query) -> None:
        started = time.monotonic()
        env = self._env(ctx.hub)
        try:
            card = await asyncio.wait_for(run_tool(env, q), TOOL_TIMEOUT_S)
        except FetchError as exc:
            card = error_card(q.tool, f"Couldn't get {LABELS[q.tool]}", str(exc))
        except asyncio.TimeoutError:
            card = error_card(q.tool, f"Couldn't get {LABELS[q.tool]}", "The sources took too long. Try again in a minute.")
        except Exception:
            log.exception("info tool %s failed on %s", q.tool, ref)
            card = error_card(q.tool, f"Couldn't get {LABELS[q.tool]}", "Something went wrong on the server.")
        await self._send(ctx, sender, ref, card, env, started)

    async def _send(self, ctx, sender, ref: str | None, card: Card, env: Env, started: float,
                    extra: dict | None = None, ttl_ms: int = ANSWER_TTL_MS) -> str | None:
        """Fits the card into one envelope, stamps what it cost, sends it down and records it."""
        hub = ctx.hub if ctx else self.hub
        meter = env.meter
        cached = meter.requests > 0 and meter.cached == meter.requests
        data = fit({**card.to_data(ref, hub.now(), meter.bytes, cached), **(extra or {}), "wire": 0})
        for _ in range(2):  # the size field itself is part of the size
            data["wire"] = hub.down_wire_size(sender.address, "info", "answer", data, ttl_ms)
        if size_of(data) > 3700:  # fit() gave up: never let one card break the envelope
            data = fit(error_card(card.tool, "Answer too large", "Try a narrower question.").to_data(ref, hub.now(), meter.bytes, cached))
            data["wire"] = hub.down_wire_size(sender.address, "info", "answer", data, ttl_ms)
        down_id = await hub.send_down(sender.address, "info", "answer", data, ref=ref, ttl_ms=ttl_ms)
        ms = int((time.monotonic() - started) * 1000)
        if ref:
            hub.db.info_answered(ref, data["title"], card.ok, card.level, meter.bytes, data["wire"], cached, ms,
                                 down_id, ", ".join(meter.sources), hub.now())
        hub.db.event("info", f"🌐 @{sender.handle} {card.tool} · {data['title']} · "
                             f"{fmt_bytes(meter.bytes)}{' (cached)' if cached else ''} fetched → {fmt_bytes(data['wire'])} to the mesh",
                     hub.now())
        return down_id

    # ---- watches ---------------------------------------------------------------------------

    async def _start_watch(self, ctx, sender, ref: str, data: dict) -> None:
        """Answers with the current alerts and remembers them, so only newer ones are pushed later."""
        started = time.monotonic()
        topic = "alerts"
        env = self._env(ctx.hub)
        try:
            q = parse_query({**data, "tool": "alerts"})
            if q.lat is None:
                raise ValueError("Watching needs your location")
        except ValueError as exc:
            ctx.annotate(summary="watch · alerts", note=str(exc))
            await self._send(ctx, sender, ref, error_card("alerts", "Couldn't start watching", str(exc)), env, started)
            return
        hours = data.get("hours")
        hours = hours if isinstance(hours, int) and 1 <= hours <= WATCH_HOURS_MAX else WATCH_HOURS_DEFAULT
        until = ctx.now() + hours * 3600_000
        ctx.annotate(summary=f"watch · alerts near {q.lat:.3f},{q.lon:.3f} for {hours} h")
        try:
            card = await asyncio.wait_for(run_tool(env, q), TOOL_TIMEOUT_S)
            seen = await self._alert_ids(env, q.lat, q.lon)
        except (FetchError, asyncio.TimeoutError) as exc:
            card = error_card("alerts", "Watching, but couldn't check alerts yet",
                              str(exc) if isinstance(exc, FetchError) else "The alert services are slow right now.")
            seen = []
        place = card.title.removeprefix("Alerts · ") if card.ok else None
        ctx.hub.db.info_watch_put(sender.address, topic, q.lat, q.lon, place, until, json.dumps(seen), ctx.now())
        ctx.hub.db.info_asked(ref, sender.address, "alerts", f"watch · {place or 'here'} · {hours} h", ctx.now())
        await self._send(ctx, sender, ref, card, env, started, extra={"watch": until})

    async def _alert_ids(self, env: Env, lat: float, lon: float) -> list[str]:
        return [a.id for a in (await survey(env, lat, lon)).here]

    async def _watch_forever(self) -> None:
        while True:
            await asyncio.sleep(self.watch_interval_s)
            try:
                await self.check_watches()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("watch check failed")

    async def check_watches(self) -> int:
        """Pushes alerts issued since each watcher last heard. Returns how many cards were sent."""
        hub = self.hub
        if hub is None:
            return 0
        sent = 0
        for w in hub.db.info_watches():
            user = hub.db.user_by_address(w["address"])
            if user is None:
                hub.db.info_watch_delete(w["address"], w["topic"])
                continue
            if w["until"] <= hub.now():
                hub.db.info_watch_delete(w["address"], w["topic"])
                await hub.send_down(user.address, "info", "watch",
                                    {"topic": w["topic"], "on": False, "reason": "expired"}, ttl_ms=WATCH_TTL_MS)
                continue
            env = self._env(hub)
            try:
                s = await survey(env, w["lat"], w["lon"])
            except FetchError:
                continue
            seen = set(json.loads(w["seen"] or "[]"))
            fresh = [a for a in s.here if a.id not in seen]
            current = [a.id for a in s.here]
            if not fresh:
                hub.db.info_watch_checked(user.address, w["topic"], json.dumps(current), hub.now())
                continue
            place = w["place"] or await reverse(env, w["lat"], w["lon"]) or "your area"
            card = Card(tool="alerts", title=f"New alert · {place}",
                        body=f"{len(fresh)} new warning{'s' if len(fresh) != 1 else ''} since you last heard.",
                        items=[a.item() for a in fresh[:6]], src=" · ".join(s.answered), level=worst(a.level for a in fresh))
            await self._send(None, user, None, card, env, time.monotonic(), extra={"watch": w["until"]}, ttl_ms=WATCH_TTL_MS)
            hub.db.info_watch_checked(user.address, w["topic"], json.dumps(current), hub.now(), sent=1)
            sent += 1
        return sent
