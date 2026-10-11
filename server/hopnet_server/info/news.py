"""Headlines by topic from publishers' RSS feeds, or a search across outlets via Google News.
Each headline costs ~150 bytes, and those that can be read in full carry a link key."""

from __future__ import annotations

from .card import Card, Item, cut_bytes
from .env import Env, Query
from .fetch import FetchError
from .text import ago, parse_feed

HINDU = "https://www.thehindu.com"
TOPICS = {
    "top": ("Top stories", f"{HINDU}/feeder/default.rss", "The Hindu"),
    "india": ("India", f"{HINDU}/news/national/feeder/default.rss", "The Hindu"),
    "kerala": ("Kerala", f"{HINDU}/news/national/kerala/feeder/default.rss", "The Hindu"),
    "world": ("World", "https://feeds.bbci.co.uk/news/world/rss.xml", "BBC News"),
    "business": ("Business", f"{HINDU}/business/feeder/default.rss", "The Hindu"),
    "sport": ("Sport", f"{HINDU}/sport/feeder/default.rss", "The Hindu"),
    "tech": ("Science & tech", f"{HINDU}/sci-tech/feeder/default.rss", "The Hindu"),
}
GOOGLE_NEWS_SEARCH = "https://news.google.com/rss/search"
MAX_ITEMS = 8


async def news(env: Env, q: Query, limit: int = MAX_ITEMS) -> Card:
    if q.q:
        return await _search(env, q.q, limit)
    label, url, outlet = TOPICS.get(q.topic or "top", TOPICS["top"])
    f = await env.fetcher.get(url, ttl=600, meter=env.meter)
    feed = parse_feed(f.body)
    if f.status != 200 or not feed:
        raise FetchError(f"{outlet} didn't send headlines")
    feed.sort(key=lambda i: i.published or 0, reverse=True)
    now = env.now()
    items, seen = [], set()
    for entry in feed:
        if entry.title in seen:
            continue
        seen.add(entry.title)
        items.append(Item(
            t=entry.title,
            s=cut_bytes(entry.summary, 170) if entry.summary != entry.title else "",
            m=" · ".join(x for x in (outlet, ago(entry.published, now)) if x),
            k=env.links.put(entry.link, entry.title) if entry.link.startswith("http") else None,
        ))
        if len(items) >= limit:
            break
    return Card(tool="news", title=f"News · {label}", body="Tap a headline to read the story.", items=items, src=outlet)


async def _search(env: Env, text: str, limit: int) -> Card:
    f = await env.fetcher.get(GOOGLE_NEWS_SEARCH, params={"q": text, "hl": "en-IN", "gl": "IN", "ceid": "IN:en"},
                              ttl=600, meter=env.meter)
    feed = parse_feed(f.body)
    if f.status != 200:
        raise FetchError("News search didn't answer")
    feed.sort(key=lambda i: i.published or 0, reverse=True)
    now = env.now()
    items = []
    for entry in feed[:limit]:
        # Google News titles end with " - Outlet"; its links are redirects that only open in a browser.
        title, _, outlet = entry.title.rpartition(" - ")
        items.append(Item(t=title or entry.title, m=" · ".join(x for x in (entry.source or outlet, ago(entry.published, now)) if x)))
    body = "Latest coverage across Indian and world outlets." if items else f"No recent news found for “{text}”."
    return Card(tool="news", title=f"News · “{cut_bytes(text, 60)}”", body=body, items=items, src="Google News")
