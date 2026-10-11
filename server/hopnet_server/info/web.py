"""The open web, a little at a time: Wikipedia summaries, web search, any page or PDF read in parts
of ~2.6 KB, and (when the server has an API key) a short AI answer grounded in a web search."""

from __future__ import annotations

import io
import re
from dataclasses import dataclass
from html.parser import HTMLParser
from urllib.parse import parse_qs, quote, urlsplit

from .card import Card, Item, cut_bytes
from .env import Env, Query
from .fetch import BROWSER_UA, FetchError
from .text import clean, domain, html_to_text

DDG = "https://html.duckduckgo.com/html/"
WIKI_LANGS = {"en", "ml", "hi", "ta", "te", "kn", "bn", "mr"}
PART_BYTES = 2600
MAX_PARTS = 40


@dataclass
class Result:
    title: str
    url: str
    snippet: str


# ---- Wikipedia ----------------------------------------------------------------------------

async def wiki(env: Env, q: Query) -> Card:
    lang = q.lang if q.lang in WIKI_LANGS else "en"
    base = f"https://{lang}.wikipedia.org"
    f = await env.fetcher.get(f"{base}/w/api.php", params={
        "action": "query", "list": "search", "srsearch": q.q, "format": "json", "srlimit": 1,
    }, ttl=86400, meter=env.meter)
    hits = ((f.json() or {}).get("query") or {}).get("search") or []
    if not hits:
        raise FetchError(f"Wikipedia has no article for “{q.q}”")
    title = hits[0]["title"]
    s = await env.fetcher.get(f"{base}/api/rest_v1/page/summary/{quote(title.replace(' ', '_'), safe='')}",
                              ttl=86400, meter=env.meter)
    page = s.json() or {}
    url = ((page.get("content_urls") or {}).get("mobile") or {}).get("page") or f"{base}/wiki/{quote(title)}"
    desc = page.get("description") or ""
    body = (f"{desc[0].upper()}{desc[1:]}.\n\n" if desc else "") + (page.get("extract") or "")
    return Card(
        tool="wiki", title=page.get("title") or title, body=body.strip(), src="Wikipedia", url=url,
        items=[Item(t="Read the full article", s="Sent in parts over the mesh", k=env.links.put(url, title))],
    )


# ---- search -------------------------------------------------------------------------------

class _DuckResults(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.results: list[Result] = []
        self._field: str | None = None
        self._buf: list[str] = []
        self._href = ""

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        cls = a.get("class") or ""
        if tag == "a" and "result__a" in cls:
            self._field, self._buf, self._href = "title", [], a.get("href") or ""
        elif "result__snippet" in cls:
            self._field, self._buf = "snippet", []

    def handle_endtag(self, tag):
        if self._field == "title" and tag == "a":
            url = _unwrap(self._href)
            if url:
                self.results.append(Result(clean("".join(self._buf)), url, ""))
            self._field = None
        elif self._field == "snippet" and tag in ("a", "div", "td"):
            if self.results and not self.results[-1].snippet:
                self.results[-1].snippet = clean("".join(self._buf))
            self._field = None

    def handle_data(self, data):
        if self._field:
            self._buf.append(data)


def _unwrap(href: str) -> str | None:
    """DuckDuckGo wraps links as //duckduckgo.com/l/?uddg=<url>; ads go through y.js."""
    if not href or "y.js" in href or "ad_provider" in href:
        return None
    if href.startswith("//"):
        href = "https:" + href
    parts = urlsplit(href)
    if parts.hostname and parts.hostname.endswith("duckduckgo.com"):
        target = parse_qs(parts.query).get("uddg", [None])[0]
        return target if target and target.startswith("http") else None
    return href if href.startswith("http") else None


async def web_search(env: Env, text: str, limit: int = 6) -> tuple[list[Result], str]:
    """Top results and the engine that gave them. Falls back to Wikipedia search."""
    try:
        f = await env.fetcher.get(DDG, params={"q": text, "kl": "in-en"}, ttl=1800, meter=env.meter, timeout=12)
        parser = _DuckResults()
        parser.feed(f.text())
        results = [r for r in parser.results if r.title][:limit]
        if results:
            return results, "DuckDuckGo"
    except FetchError:
        pass
    f = await env.fetcher.get("https://en.wikipedia.org/w/api.php", params={
        "action": "query", "list": "search", "srsearch": text, "format": "json", "srlimit": limit,
    }, ttl=1800, meter=env.meter)
    hits = ((f.json() or {}).get("query") or {}).get("search") or []
    return [Result(h["title"], f"https://en.m.wikipedia.org/wiki/{quote(h['title'].replace(' ', '_'))}",
                   clean(re.sub(r"<[^>]+>", "", h.get("snippet", "")))) for h in hits], "Wikipedia"


def _result_items(env: Env, results: list[Result]) -> list[Item]:
    return [Item(t=r.title, s=cut_bytes(r.snippet, 200), m=domain(r.url), k=env.links.put(r.url, r.title))
            for r in results]


async def search(env: Env, q: Query) -> Card:
    results, engine = await web_search(env, q.q)
    if not results:
        raise FetchError(f"No web results for “{q.q}”")
    return Card(tool="search", title=f"Web · “{cut_bytes(q.q, 60)}”", body="Tap a result to read the page.",
                items=_result_items(env, results), src=engine)


async def ask(env: Env, q: Query) -> Card:
    """A short AI answer with its sources when the server has an API key; otherwise web results."""
    if env.ai is not None and env.ai.enabled:
        try:
            answer = await env.ai.answer(env, q.q)
            return Card(tool="ask", title=cut_bytes(q.q, 90), body=answer.text, ai=True, src=answer.engine,
                        items=_result_items(env, answer.sources[:4]))
        except FetchError:
            pass  # fall through to plain results
    card = await search(env, q)
    card.tool = "ask"
    card.body = ("AI answers are off on this server, so here are the top web results. Tap one to read it."
                 if env.ai is None or not env.ai.enabled else "The AI didn't answer, so here are web results.")
    return card


# ---- reader -------------------------------------------------------------------------------

async def read(env: Env, q: Query) -> Card:
    if q.key:
        found = env.links.get(q.key)
        if not found:
            raise FetchError("That link has expired. Search again to get a fresh one.")
        url, hint = found
    else:
        url, hint = (q.url if re.match(r"^https?://", q.url, re.I) else "https://" + q.url), ""
    key = q.key or env.links.put(url, hint)
    # We say who we are (Wikipedia and others block disguised bots); a site that refuses gets a browser UA.
    accept = {"Accept": "text/html,application/pdf,text/plain;q=0.9,*/*;q=0.5"}
    f = await env.fetcher.get(url, headers=accept, guard=True, ttl=1800, max_bytes=6_000_000, meter=env.meter, timeout=20)
    if f.status in (403, 406):
        f = await env.fetcher.get(url, headers={**accept, "User-Agent": BROWSER_UA}, guard=True, max_bytes=6_000_000,
                                  meter=env.meter, timeout=20)
    if f.status >= 400:
        raise FetchError(f"{domain(f.url)} answered {f.status}: the page may be gone or blocked")
    title, paragraphs = _document(f)
    if not paragraphs:
        raise FetchError("No readable text on that page (it may need a full browser)")
    parts = split_parts(paragraphs)
    cut = len(parts) > MAX_PARTS
    parts = parts[:MAX_PARTS]
    n = min(q.part, len(parts))
    body = parts[n - 1]
    if cut and n == len(parts):
        body += "\n\n[The rest of this page was too long to send.]"
    return Card(tool="read", title=title or hint or domain(f.url), body=body, src=domain(f.url), url=f.url,
                part=n, parts=len(parts), key=key)


def _document(f) -> tuple[str, list[str]]:
    ctype = f.content_type.split(";")[0].strip().lower()
    if ctype == "application/pdf" or f.body[:5] == b"%PDF-":
        return _pdf(f.body)
    if ctype in ("text/plain", "text/markdown"):
        return "", [p.strip() for p in re.split(r"\n\s*\n", f.text()) if p.strip()]
    if "html" in ctype or "xml" in ctype or not ctype:
        page = html_to_text(f.text())
        return page.title, page.paragraphs
    raise FetchError(f"That link is a {ctype or 'file'}, which can't be shown as text")


def _pdf(data: bytes) -> tuple[str, list[str]]:
    try:
        from pypdf import PdfReader
    except ImportError as exc:
        raise FetchError("Reading PDFs needs pypdf on the server (pip install pypdf)") from exc
    try:
        reader = PdfReader(io.BytesIO(data))
        title = clean(str((reader.metadata or {}).get("/Title") or ""))
        paragraphs = []
        for page in reader.pages[:80]:
            text = page.extract_text() or ""
            paragraphs += [clean(p) for p in re.split(r"\n\s*\n|(?<=[.:;])\n", text) if clean(p)]
    except Exception as exc:
        raise FetchError("Couldn't read that PDF (it may be scanned images)") from exc
    return title, paragraphs


def split_parts(paragraphs: list[str], limit: int = PART_BYTES) -> list[str]:
    """Groups paragraphs into parts of at most [limit] UTF-8 bytes; splits giant paragraphs at sentences."""
    pieces: list[str] = []
    for p in paragraphs:
        while len(p.encode("utf-8")) > limit:
            head = p.encode("utf-8")[:limit].decode("utf-8", errors="ignore")
            stop = max(head.rfind(". "), head.rfind("? "), head.rfind("! "), head.rfind("। "))
            stop = stop + 1 if stop > limit // 3 else (head.rfind(" ") if head.rfind(" ") > limit // 3 else len(head))
            pieces.append(head[:stop].strip())
            p = p[stop:].strip()
        if p:
            pieces.append(p)
    parts: list[str] = []
    current = ""
    for piece in pieces:
        joined = f"{current}\n\n{piece}" if current else piece
        if len(joined.encode("utf-8")) > limit and current:
            parts.append(current)
            current = piece
        else:
            current = joined
    if current:
        parts.append(current)
    return parts
