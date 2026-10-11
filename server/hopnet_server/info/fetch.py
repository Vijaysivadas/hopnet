"""Outbound HTTP for Live Info: one shared client, a small response cache, byte accounting and
guards so a phone can't make the server fetch its own network (SSRF)."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import socket
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Awaitable, Callable
from urllib.parse import urljoin, urlsplit

import httpx

USER_AGENT = "HopnetServer/0.1 (offline mesh info relay; +https://github.com/hopnet)"
BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HopnetReader/0.1"
MAX_REDIRECTS = 5
CACHE_ENTRIES = 400

Resolver = Callable[[str, int], Awaitable[list[str]]]


class FetchError(Exception):
    """A failure worth telling the phone about, phrased for a person."""


@dataclass
class Fetched:
    url: str            # final URL, after redirects
    status: int
    content_type: str
    body: bytes
    size: int           # bytes that came over the internet for this response
    cached: bool = False
    truncated: bool = False

    def text(self) -> str:
        charset = "utf-8"
        for part in self.content_type.split(";")[1:]:
            k, _, v = part.strip().partition("=")
            if k.lower() == "charset" and v:
                charset = v.strip('"\' ')
        try:
            return self.body.decode(charset, errors="replace")
        except LookupError:
            return self.body.decode("utf-8", errors="replace")

    def json(self):
        try:
            return json.loads(self.body)
        except ValueError as exc:
            raise FetchError("The source sent something unreadable") from exc


@dataclass
class Meter:
    """Bytes one answer pulled from the internet (what the server spent so the mesh doesn't)."""
    bytes: int = 0
    requests: int = 0
    cached: int = 0
    sources: list[str] = field(default_factory=list)

    def add(self, f: Fetched) -> None:
        self.bytes += f.size
        self.requests += 1
        self.cached += int(f.cached)
        host = urlsplit(f.url).hostname or ""
        if host and host not in self.sources:
            self.sources.append(host)


async def system_resolver(host: str, port: int) -> list[str]:
    infos = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return [info[4][0] for info in infos]


def is_public_ip(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_global and not ip.is_multicast


class Fetcher:
    """`get` follows redirects itself so every hop of a user-supplied URL can be checked."""

    def __init__(self, transport: httpx.AsyncBaseTransport | None = None, resolver: Resolver | None = None,
                 clock: Callable[[], float] = time.monotonic):
        self._transport = transport
        self._resolver = resolver or system_resolver
        self._clock = clock
        self._client: httpx.AsyncClient | None = None
        self._cache: OrderedDict[str, tuple[float, Fetched]] = OrderedDict()

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                transport=self._transport,
                timeout=httpx.Timeout(15.0, connect=6.0),
                follow_redirects=False,
                headers={"User-Agent": USER_AGENT, "Accept-Language": "en-IN,en;q=0.8"},
            )
        return self._client

    def clear_cache(self) -> None:
        self._cache.clear()

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def get(self, url: str, *, params: dict | None = None, headers: dict | None = None, ttl: float = 0,
                  max_bytes: int = 3_000_000, guard: bool = False, method: str = "GET",
                  json_body: dict | None = None, form: dict | None = None, timeout: float | None = None,
                  meter: Meter | None = None) -> Fetched:
        """Fetches [url]. `ttl` seconds > 0 serves and stores a cached copy. `guard` refuses private
        and local addresses, for URLs that came from a phone."""
        key = json.dumps([method, url, sorted((params or {}).items()), json_body, form], default=str)
        if ttl > 0:
            hit = self._cache.get(key)
            if hit and hit[0] > self._clock():
                self._cache.move_to_end(key)
                cached = Fetched(**{**hit[1].__dict__, "cached": True})
                if meter:
                    meter.add(cached)
                return cached

        fetched = await self._fetch(url, params, headers, max_bytes, guard, method, json_body, form, timeout)
        if ttl > 0 and 200 <= fetched.status < 300:
            self._cache[key] = (self._clock() + ttl, fetched)
            self._cache.move_to_end(key)
            while len(self._cache) > CACHE_ENTRIES:
                self._cache.popitem(last=False)
        if meter:
            meter.add(fetched)
        return fetched

    async def _fetch(self, url, params, headers, max_bytes, guard, method, json_body, form, timeout) -> Fetched:
        client = self._http()
        total = 0
        for _ in range(MAX_REDIRECTS + 1):
            if guard:
                await self.check_url(url)
            request = client.build_request(method, url, params=params, headers=headers, json=json_body, data=form,
                                           timeout=timeout if timeout else httpx.USE_CLIENT_DEFAULT)
            try:
                resp = await client.send(request, stream=True)
            except httpx.TimeoutException as exc:
                raise FetchError(f"{_host(url)} took too long to answer") from exc
            except httpx.HTTPError as exc:
                raise FetchError(f"Couldn't reach {_host(url)}") from exc
            try:
                if resp.is_redirect and resp.headers.get("location"):
                    total += resp.num_bytes_downloaded
                    url = urljoin(str(resp.url), resp.headers["location"])
                    params, method, json_body, form = None, "GET", None, None
                    continue
                body = bytearray()
                truncated = False
                try:
                    async for chunk in resp.aiter_bytes():
                        body += chunk
                        if len(body) > max_bytes:
                            del body[max_bytes:]
                            truncated = True
                            break
                except httpx.HTTPError as exc:
                    raise FetchError(f"{_host(url)} stopped halfway") from exc
                # Bytes on the wire (compressed); in-memory transports report 0, so fall back to the body.
                total += resp.num_bytes_downloaded or len(body)
                return Fetched(str(resp.url), resp.status_code, resp.headers.get("content-type", ""), bytes(body),
                               total, truncated=truncated)
            finally:
                await resp.aclose()
        raise FetchError("Too many redirects")

    async def check_url(self, url: str) -> None:
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise FetchError("Only http and https links can be opened")
        if parts.username or parts.password:
            raise FetchError("Links with passwords can't be opened")
        host = parts.hostname
        if host.endswith((".local", ".internal", ".localhost")) or host == "localhost":
            raise FetchError("That address isn't on the public internet")
        try:
            ipaddress.ip_address(host)
            addresses = [host]
        except ValueError:
            try:
                addresses = await self._resolver(host, parts.port or (443 if parts.scheme == "https" else 80))
            except OSError as exc:
                raise FetchError(f"Couldn't find {host}") from exc
        if not addresses or not all(is_public_ip(a) for a in addresses):
            raise FetchError("That address isn't on the public internet")


def _host(url: str) -> str:
    return urlsplit(url).hostname or "the source"
