"""Turning web pages and feeds into the few lines a phone needs, plus small formatting helpers."""

from __future__ import annotations

import html
import math
import re
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from urllib.parse import urlsplit

# ---- HTML → readable text ---------------------------------------------------------------

SKIP = {"script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "iframe", "button",
        "select", "option", "template", "figure", "figcaption", "canvas", "video", "audio", "menu", "dialog"}
BLOCK = {"p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "br", "tr", "section", "article", "main",
         "blockquote", "pre", "table", "ul", "ol", "dd", "dt", "hr", "td", "th"}
VOID = {"br", "hr", "img", "input", "meta", "link", "source", "wbr", "area", "base", "col", "embed", "param", "track"}
# Page furniture, matched against whole class/id tokens ("infobox", "mw-editsection", "share-bar"), never on
# page-level elements: Wikipedia's <html> carries classes like "vector-toc-available".
JUNK_WORDS = ("infobox", "navbox", "reflist", "references", "reference", "editsection", "sidebar", "hatnote",
              "metadata", "toc", "cookie", "consent", "advert", "ads", "ad", "share", "social", "related",
              "newsletter", "comments", "comment", "breadcrumb", "breadcrumbs", "promo", "subscribe", "popup", "modal")
CONTAINERS = {"html", "body", "main", "article"}
CITATION = re.compile(r"\s*\[\s*(\d+|[a-z]|note \d+|citation needed|clarification needed|when\?)\s*\]", re.I)
JUNK = re.compile(r"^(advertisement|ad|sponsored|subscribe|sign in|log in|share|follow us|read more|also read|"
                  r"click here|cookie|accept|loading|related|trending|copyright|all rights reserved)\b", re.I)


class _Readable(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.og_title = ""
        self.blocks: list[tuple[str, str]] = []   # (tag, text)
        self.article: list[tuple[str, str]] = []  # blocks inside <article>/<main>
        self._skip = 0
        self._in_title = False
        self._in_article = 0
        self._tag_stack: list[str] = []
        self._buf: list[str] = []
        self._block_tag = "p"

    def handle_starttag(self, tag, attrs):
        if tag == "meta":
            a = dict(attrs)
            if a.get("property") == "og:title" and a.get("content"):
                self.og_title = a["content"]
            return
        if tag in VOID:
            if tag in BLOCK:
                self._flush()
            return
        if tag in SKIP or self._hidden(tag, attrs):
            self._skip += 1
            self._tag_stack.append("!" + tag)
            return
        self._tag_stack.append(tag)
        if tag == "title" and self._skip <= 0 and not self.title:
            self._in_title = True
        if tag in ("article", "main"):
            self._in_article += 1
        if tag in BLOCK:
            self._flush()
            self._block_tag = tag

    def handle_endtag(self, tag):
        # Pop to the matching tag; real pages are full of unclosed elements.
        for i in range(len(self._tag_stack) - 1, -1, -1):
            name = self._tag_stack[i]
            if name.lstrip("!") == tag:
                for closed in self._tag_stack[i:]:
                    if closed.startswith("!"):
                        self._skip -= 1
                    elif closed in ("article", "main"):
                        self._flush()
                        self._in_article -= 1
                del self._tag_stack[i:]
                break
        if tag == "title":
            self._in_title = False
        if tag in BLOCK:
            self._flush()

    def handle_data(self, data):
        if self._in_title:
            self.title += data
            return
        if self._skip <= 0:
            self._buf.append(data)

    def _hidden(self, tag, attrs) -> bool:
        a = dict(attrs)
        style = (a.get("style") or "").replace(" ", "").lower()
        if "hidden" in a or a.get("aria-hidden") == "true" or "display:none" in style:
            return True
        if tag in CONTAINERS:
            return False
        if a.get("role") in ("navigation", "banner", "contentinfo", "complementary"):
            return True
        return any(_junk_token(tok) for tok in f"{a.get('class') or ''} {a.get('id') or ''}".lower().split())

    def _flush(self):
        # Inline elements touch ("<a>Kerala</a>, India"): join as-is and let clean() fold whitespace.
        text = CITATION.sub("", clean("".join(self._buf)))
        self._buf = []
        if text:
            block = (self._block_tag, text)
            self.blocks.append(block)
            if self._in_article > 0:
                self.article.append(block)


def _junk_token(token: str) -> bool:
    for word in JUNK_WORDS:
        if token == word or token.startswith((word + "-", word + "_")) or token.endswith(("-" + word, "_" + word)):
            return True
    return False


@dataclass
class Page:
    title: str
    paragraphs: list[str]


def html_to_text(markup: str) -> Page:
    """The readable part of a page: the <article>/<main> when the page has a real one, otherwise every
    paragraph-like block, minus navigation, buttons and boilerplate."""
    parser = _Readable()
    try:
        parser.feed(markup)
        parser.close()
    except Exception:  # malformed markup: keep what was parsed
        pass
    parser._flush()
    blocks = parser.article if sum(len(t) for _, t in parser.article) > 400 else parser.blocks
    out: list[str] = []
    seen = set()
    for tag, text in blocks:
        heading = tag in ("h1", "h2", "h3", "h4")
        if JUNK.match(text) or (len(text) < 40 and not heading and tag != "li") or len(text) < 3:
            continue
        if text in seen:
            continue
        seen.add(text)
        out.append(("• " + text) if tag == "li" else text)
    # The page's own headline beats <title>, which usually ends in " | Site name".
    title = clean(parser.og_title or parser.title)
    first_h1 = next((text for tag, text in blocks if tag == "h1"), None)
    if first_h1:
        title = first_h1
    if out and out[0] == title:
        out = out[1:]
    return Page(title=title, paragraphs=out)


def clean(text: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(text or "")).strip()


def strip_tags(markup: str) -> str:
    return clean(re.sub(r"<[^>]+>", " ", markup or ""))


# ---- feeds ------------------------------------------------------------------------------

@dataclass
class FeedItem:
    title: str
    link: str
    published: int | None   # epoch ms
    source: str
    summary: str
    extra: dict


def parse_feed(data: bytes) -> list[FeedItem]:
    """RSS 2.0 and Atom. `extra` keeps namespaced fields (GDACS alert level, geo points...) by local name."""
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return []
    items: list[FeedItem] = []
    nodes = [n for n in root.iter() if _local(n.tag) in ("item", "entry")]
    for node in nodes:
        fields: dict[str, str] = {}
        link = ""
        source = ""
        for child in node:
            name = _local(child.tag)
            text = (child.text or "").strip()
            if name == "link":
                link = link or text or child.get("href", "")
            elif name == "source":
                source = text or child.get("url", "")
            elif name == "Point":  # geo:Point with geo:lat / geo:long children
                for sub in child:
                    fields[_local(sub.tag)] = (sub.text or "").strip()
            elif name not in fields:
                fields[name] = text
        title = clean(fields.get("title", ""))
        if not title:
            continue
        published = _parse_date(fields.get("pubDate") or fields.get("published") or fields.get("updated") or "")
        summary = strip_tags(fields.get("description") or fields.get("summary") or "")
        items.append(FeedItem(title, link, published, clean(source), summary, fields))
    return items


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def _parse_date(value: str) -> int | None:
    if not value:
        return None
    try:
        return int(parsedate_to_datetime(value).timestamp() * 1000)
    except (TypeError, ValueError, IndexError):
        pass
    try:
        from datetime import datetime
        return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


# ---- formatting -------------------------------------------------------------------------

def ago(ms: int | None, now_ms: int | None = None) -> str:
    if not ms:
        return ""
    now_ms = now_ms or int(time.time() * 1000)
    s = max(0, (now_ms - ms) // 1000)
    if s < 90:
        return "just now"
    if s < 3600:
        return f"{s // 60} min ago"
    if s < 48 * 3600:
        return f"{s // 3600} h ago"
    return f"{s // 86400} days ago"


def fmt_bytes(n: int) -> str:
    if n < 1000:
        return f"{n} B"
    if n < 1_000_000:
        return f"{n / 1000:.1f} KB"
    return f"{n / 1_000_000:.1f} MB"


def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def bearing(lat1: float, lon1: float, lat2: float, lon2: float) -> str:
    """Compass direction from point 1 to point 2: N, NE, E..."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    deg = (math.degrees(math.atan2(x, y)) + 360) % 360
    return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int((deg + 22.5) // 45) % 8]


def fmt_distance(km: float) -> str:
    if km < 1:
        return f"{max(10, round(km * 1000, -1)):.0f} m"
    if km < 10:
        return f"{km:.1f} km"
    return f"{km:,.0f} km"


def domain(url: str) -> str:
    host = urlsplit(url).hostname or ""
    return host[4:] if host.startswith("www.") else host
