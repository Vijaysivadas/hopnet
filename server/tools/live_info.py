"""Runs Live Info tools against the real internet and prints each answer card with what it cost.

Use it before a demo to check every source is up (public map servers in particular come and go):

  python -m tools.live_info                     # every tool, for Aluva
  python -m tools.live_info weather nearby      # just these
  python -m tools.live_info --lat 9.93 --lon 76.26 --place Kochi alerts

With ANTHROPIC_API_KEY set, `ask` gives an AI answer; otherwise web results.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time

from hopnet_server.db import Database
from hopnet_server.info import Env, Query, run_tool
from hopnet_server.info.ai import AI
from hopnet_server.info.card import fit, size_of
from hopnet_server.info.env import TOOLS, Links
from hopnet_server.info.fetch import Fetcher
from hopnet_server.info.text import fmt_bytes


def cases(lat: float, lon: float, place: str) -> list[dict]:
    here = {"lat": lat, "lon": lon}
    return [
        {"tool": "brief", **here},
        {"tool": "weather", **here},
        {"tool": "weather", "place": place},
        {"tool": "alerts", **here},
        {"tool": "nearby", "cat": "hospital", **here},
        {"tool": "nearby", "cat": "shelter", **here},
        {"tool": "news", "topic": "kerala"},
        {"tool": "news", "q": f"{place} rain"},
        {"tool": "wiki", "q": "Periyar river"},
        {"tool": "search", "q": "kochi airport flight status"},
        {"tool": "ask", "q": "what to do during a flood"},
        {"tool": "read", "url": "https://en.wikipedia.org/wiki/2018_Kerala_floods"},
    ]


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tools", nargs="*", help=f"tools to run (default: all of {', '.join(TOOLS)})")
    parser.add_argument("--lat", type=float, default=10.1076)
    parser.add_argument("--lon", type=float, default=76.3516)
    parser.add_argument("--place", default="Aluva")
    parser.add_argument("--full", action="store_true", help="print whole cards, not the first lines")
    args = parser.parse_args()

    db = Database(":memory:")
    fetcher = Fetcher()
    now = lambda: int(time.time() * 1000)  # noqa: E731
    ok = True
    for case in cases(args.lat, args.lon, args.place):
        if args.tools and case["tool"] not in args.tools:
            continue
        q = Query(**case)
        env = Env(fetcher, Links(db, now), now, AI.from_env())
        started = time.monotonic()
        try:
            card = await asyncio.wait_for(run_tool(env, q), 70)
        except Exception as exc:  # report every failure and keep going
            ok = False
            print(f"\n✗ {q.describe()}  FAILED after {time.monotonic() - started:.1f}s: {exc}")
            continue
        data = fit(card.to_data(None, now(), env.meter.bytes, False))
        print(f"\n{'✓' if card.ok else '✗'} {q.describe()}  [{time.monotonic() - started:.1f}s · "
              f"{fmt_bytes(env.meter.bytes)} from {', '.join(env.meter.sources) or 'cache'} → card {fmt_bytes(size_of(data))}]")
        text = json.dumps(data, ensure_ascii=False, indent=1)
        print(text if args.full else "\n".join(text.splitlines()[:14]))
    await fetcher.close()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    asyncio.run(main())
