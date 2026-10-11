"""A self-refreshing status page for the demo projector: every message's hop path, all traffic,
gateways and users."""

from __future__ import annotations

import time
from html import escape


def _clock(ms: int | None) -> str:
    return time.strftime("%H:%M:%S", time.localtime(ms / 1000)) if ms else "—"


def _ago(ms: int | None) -> str:
    if not ms:
        return "—"
    s = max(0, int(time.time() - ms / 1000))
    if s < 60:
        return f"{s}s ago"
    if s < 3600:
        return f"{s // 60}m ago"
    return f"{s // 3600}h ago"


def _hops(path: str | None) -> list[str]:
    return [p for p in (path or "").split(">") if p]


def _pills(hops: list[str], cls: str = "hop") -> str:
    return '<span class="arrow">›</span>'.join(f'<span class="{cls}">{escape(h)}</span>' for h in hops)


def _path(path: str | None) -> str:
    hops = _hops(path)
    return _pills(hops) if hops else '<span class="muted">—</span>'


SERVER = '<span class="link">⟶</span><span class="cloud">☁ server</span><span class="link">⟶</span>'


def _journey(m: dict) -> str:
    """Sender's path up to the server, then the path down to the recipient."""
    up = _pills(_hops(m["upPath"])) or '<span class="muted">?</span>'
    if m["rejected"]:
        return f'{up}{SERVER}<span class="bad">✗ rejected</span>'
    if m["note"]:
        return f'{up}{SERVER}<span class="bad">✗ {escape(m["note"])} @{escape(m["to"] or "?")}</span>'
    if m["downPath"]:
        down = [h for h in _hops(m["downPath"]) if h != "cloud"]
        return f"{up}{SERVER}{_pills(down)}"
    via = m["pushedVia"]
    if m["deliveredAt"]:
        # Recipient's app is older and didn't report its path: show the gateway we handed it to.
        known = _pills(via) + '<span class="arrow">›</span>' if via else ""
        return f'{up}{SERVER}{known}<span class="hop dim">@{escape(m["to"] or "?")}</span>'
    if via:
        return f'{up}{SERVER}{_pills(via)}<span class="arrow">›</span><span class="wait">⏳ on its way to @{escape(m["to"] or "?")}</span>'
    return f'{up}{SERVER}<span class="wait">⏳ waiting for a gateway near @{escape(m["to"] or "?")}</span>'


def _hop_count(m: dict) -> str:
    up = m["upHops"] if m["upHops"] is not None else "?"
    if m["downPath"]:
        return f"{up} up · {m['downHops']} down"
    return f"{up} up"


def _status(m: dict) -> str:
    if m["rejected"] or m["note"]:
        return '<span class="bad">failed</span>'
    if m["deliveredAt"]:
        secs = max(0, (m["deliveredAt"] - m["at"]) // 1000)
        return f'<span class="ok">✓✓ delivered</span><div class="muted small">{_clock(m["deliveredAt"])} · {secs}s after upload</div>'
    return '<span class="wait-text">waiting</span>'


def _bytes(n: int | None) -> str:
    if n is None:
        return "—"
    if n < 1000:
        return f"{n} B"
    if n < 1_000_000:
        return f"{n / 1000:.1f} KB"
    return f"{n / 1_000_000:.1f} MB"


def _info_cost(q: dict) -> str:
    """What the server pulled from the internet vs what the mesh had to carry back."""
    if q["answeredAt"] is None:
        return '<span class="wait">⏳ fetching…</span>'
    ratio = f'<div class="muted small">{q["fetched"] / q["wire"]:,.0f}× smaller</div>' if q["wire"] and q["fetched"] and q["fetched"] > q["wire"] else ""
    cached = ' <span class="muted small">(cached)</span>' if q["cached"] else ""
    return f'{_bytes(q["fetched"])}{cached} <span class="arrow">⟶</span> <b class="ok">{_bytes(q["wire"])}</b>{ratio}'


def _info_answer(q: dict) -> str:
    cls = "bad" if q["ok"] is False else "warn" if q["level"] in ("warn", "danger") else ""
    return (f'<span class="{cls}">{escape(q["title"] or "…")}</span>'
            f'<div class="muted small">{escape(q["sources"] or "")}</div>')


def _info_journey(q: dict) -> str:
    up = _pills(_hops(q["upPath"])) or '<span class="muted">?</span>'
    if q["answeredAt"] is None:
        return f'{up}{SERVER}<span class="wait">⏳ reading the internet</span>'
    if q["downPath"]:
        return f'{up}{SERVER}{_pills([h for h in _hops(q["downPath"]) if h != "cloud"])}'
    if q["deliveredAt"]:  # the phone didn't report its path: show the gateway it went to
        known = _pills(q["pushedVia"]) + '<span class="arrow">›</span>' if q["pushedVia"] else ""
        return f'{up}{SERVER}{known}<span class="hop dim">@{escape(q["from"] or "?")}</span>'
    if q["pushedVia"]:
        return f'{up}{SERVER}{_pills(q["pushedVia"])}<span class="arrow">›</span><span class="wait">⏳ on its way</span>'
    return f'{up}{SERVER}<span class="wait">⏳ waiting for a gateway near @{escape(q["from"] or "?")}</span>'


def _info_status(q: dict) -> str:
    if q["answeredAt"] is None:
        return '<span class="wait-text">answering</span>'
    took = f'<div class="muted small">answered in {q["ms"] / 1000:.1f}s</div>' if q["ms"] is not None else ""
    if q["deliveredAt"]:
        return f'<span class="ok">✓✓ delivered</span>{took}'
    return f'<span class="wait-text">sent</span>{took}'


def _findme_where(c: dict) -> str:
    if c["lat"] is None or c["lon"] is None:
        return '<span class="muted">no GPS fix yet</span>'
    acc = f" ±{c['acc']:.0f} m" if c["acc"] is not None else ""
    return f"{c['lat']:.5f}, {c['lon']:.5f}{acc}"


def render_debug_page(state: dict) -> str:
    msgs = state["messages"]
    delivered = sum(1 for m in msgs if m["deliveredAt"])
    totals = state["infoTotals"]
    saved = (f" · {totals['fetched'] / totals['wire']:,.0f}× smaller" if totals["wire"] and totals["fetched"] > totals["wire"] else "")

    info = "".join(
        f"<tr><td class='muted'>{_clock(q['at'])}</td><td><b>@{escape(q['from'] or '?')}</b></td>"
        f"<td><span class='tag tag-info'>{escape(q['tool'])}</span> {escape(q['query'].split(' · ', 1)[-1])}</td>"
        f"<td class='text'>{_info_answer(q)}</td>"
        f"<td class='nowrap'>{_info_cost(q)}</td>"
        f"<td class='journey'>{_info_journey(q)}</td>"
        f"<td class='nowrap'>{_info_status(q)}</td></tr>"
        for q in state["info"]
    ) or '<tr><td colspan="7" class="muted">Nobody has asked for live info yet</td></tr>'
    lost = sum(1 for c in state["findme"] if c["status"] == "lost")

    findme = "".join(
        f"<tr><td><b>{escape(c['name'])}</b><div class='muted small'>"
        f"{escape('@' + c['handle']) if c['handle'] else 'unverified'}</div></td>"
        f"<td>{'<span class=bad>needs help</span>' if c['status'] == 'lost' else '<span class=ok>found</span>'}</td>"
        f"<td class='nowrap'>{_findme_where(c)}</td>"
        f"<td class='text'>{escape(c['note'] or '')}</td>"
        f"<td class='journey'>{_path(c['path'])}</td>"
        f"<td class='muted'>{_ago(c['receivedAt'])}</td></tr>"
        for c in state["findme"][:20]
    ) or '<tr><td colspan="6" class="muted">Nobody has used Find Me yet</td></tr>'

    messages = "".join(
        f"<tr><td class='muted'>{_clock(m['at'])}</td>"
        f"<td><b>@{escape(m['from'] or '?')}</b><div class='muted small'>to @{escape(m['to'] or '?')}</div></td>"
        f"<td class='text'>{escape(m['text'] or '')}</td>"
        f"<td class='journey'>{_journey(m)}</td>"
        f"<td class='muted nowrap'>{_hop_count(m)}</td>"
        f"<td class='nowrap'>{_status(m)}</td></tr>"
        for m in msgs
    ) or '<tr><td colspan="6" class="muted">No messages yet</td></tr>'

    traffic = "".join(
        f"<tr><td class='muted'>{_clock(t['at'])}</td><td>@{escape(t['from'] or '?')}</td>"
        f"<td><span class='tag tag-{escape(t['svc'] or 'x')}'>{escape(t['svc'] or '?')}/{escape(t['kind'] or '?')}</span></td>"
        f"<td>{escape(t['summary'] or '')}{(' <span class=bad>' + escape(t['note']) + '</span>') if t['note'] else ''}</td>"
        f"<td class='journey'>{_path(t['path'])}</td><td class='muted'>{t['hops']}</td>"
        f"<td class='{'ok' if t['status'] == 'RECEIVED' else 'bad'}'>{escape(t['status'])}</td></tr>"
        for t in state["traffic"]
    ) or '<tr><td colspan="7" class="muted">Nothing has reached the server yet</td></tr>'

    gateways = "".join(
        f"<tr><td><span class='hop'>{escape(g['label'])}</span></td><td>{escape('@' + g['owner'] if g['owner'] else '—')}</td>"
        f"<td>{escape(', '.join('@' + h for h in g['reaches']) or '—')}</td><td>{g['uploads']}</td>"
        f"<td>{_ago(g['since'])}</td></tr>"
        for g in state["gateways"]
    ) or '<tr><td colspan="5" class="muted">No phone is online right now: messages wait in the mesh</td></tr>'

    users = "".join(
        f"<tr><td><b>@{escape(u['handle'])}</b></td><td>{escape(u['name'])}</td>"
        f"<td class='journey'>{_path(u['via'])}</td><td>{_ago(u['lastSeen'])}</td></tr>"
        for u in state["users"]
    ) or '<tr><td colspan="4" class="muted">No users yet</td></tr>'

    events = "".join(
        f"<tr><td class='muted'>{_clock(e['at'])}</td><td>{escape(e['text'])}</td></tr>"
        for e in state["events"][:40]
    ) or '<tr><td colspan="2" class="muted">Nothing yet</td></tr>'

    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta http-equiv="refresh" content="2">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hopnet server</title>
<style>
  :root {{ --bg:#0f1720; --card:#17212c; --line:#22303d; --ink:#e6edf3; --muted:#8b98a5;
          --accent:#2dd4bf; --accent-bg:#0b3b37; --warn:#fbbf24; --warn-bg:#3d2f0a; --bad:#f87171; }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; font:15px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--ink); }}
  header {{ padding:18px 24px; border-bottom:1px solid var(--line); display:flex; flex-wrap:wrap; gap:8px 16px; align-items:baseline; }}
  header h1 {{ margin:0; font-size:22px; }} header .sub {{ color:var(--muted); }}
  .stats {{ margin-left:auto; display:flex; gap:8px; flex-wrap:wrap; }}
  .stat {{ background:var(--card); border-radius:999px; padding:4px 12px; font-size:13px; }}
  .stat b {{ color:var(--accent); }}
  main {{ display:grid; grid-template-columns:repeat(auto-fit, minmax(min(460px, 100%), 1fr)); gap:16px; padding:16px 24px; }}
  section {{ background:var(--card); border-radius:12px; padding:14px 16px; overflow-x:auto; min-width:0; }}
  section.wide {{ grid-column:1 / -1; }}
  h2 {{ margin:0 0 10px; font-size:13px; text-transform:uppercase; letter-spacing:.06em; color:var(--muted); font-weight:600; }}
  table {{ width:100%; border-collapse:collapse; }}
  td, th {{ padding:7px 8px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }}
  th {{ color:var(--muted); font-weight:500; font-size:13px; }}
  .muted {{ color:var(--muted); }} .small {{ font-size:12px; }} .nowrap {{ white-space:nowrap; }}
  .ok {{ color:var(--accent); }} .bad {{ color:var(--bad); }} .wait-text {{ color:var(--warn); }}
  .text {{ max-width:260px; }}
  .journey {{ white-space:nowrap; }}
  .hop {{ display:inline-block; padding:1px 9px; border-radius:999px; background:var(--accent-bg); color:var(--accent); font-weight:600; font-size:13px; }}
  .hop.dim {{ opacity:.6; }}
  .cloud {{ display:inline-block; padding:1px 9px; border-radius:999px; border:1px solid var(--accent); color:var(--ink); font-size:13px; }}
  .wait {{ display:inline-block; padding:1px 9px; border-radius:999px; background:var(--warn-bg); color:var(--warn); font-size:13px; }}
  .arrow {{ color:var(--muted); padding:0 3px; }}
  .link {{ color:var(--muted); padding:0 6px; }}
  .tag {{ font-family:ui-monospace, monospace; font-size:12px; padding:1px 6px; border-radius:6px; background:#22303d; }}
  .tag-chat {{ background:#2b2777; color:#e0e0ff; }}
  .tag-findme {{ background:#3b1414; color:#fecaca; }}
  .tag-info {{ background:#0b3b37; color:#b4f5ea; }}
  .warn {{ color:var(--warn); }}
  header a {{ color:var(--accent); }}
</style></head><body>
<header><h1>Hopnet server</h1><span class="sub">post office for the offline mesh · refreshes every 2s</span>
<div class="stats"><span class="stat"><b>{len(state["gateways"])}</b> gateways online</span>
<span class="stat"><b>{len(state["users"])}</b> users</span>
<span class="stat"><b>{delivered}</b>/{len(msgs)} messages delivered</span>
<span class="stat"><b>{totals["answered"]}</b> lookups · {_bytes(totals["fetched"])} fetched ⟶ <b>{_bytes(totals["wire"])}</b> over the mesh{saved}</span>
<span class="stat"><b>{lost}</b> need help · <a href="/police">Find Me police page</a></span></div></header>
<main>
<section class="wide"><h2>Messages · hop path (sender ⟶ server ⟶ recipient)</h2><table>
<tr><th>Time</th><th>From</th><th>Message</th><th>Hop path</th><th>Hops</th><th>Status</th></tr>{messages}</table></section>
<section class="wide"><h2>Live Info · the server reads the internet for phones that can't (fetched ⟶ carried by the mesh)</h2><table>
<tr><th>Time</th><th>Who</th><th>Asked</th><th>Answer</th><th>Internet ⟶ mesh</th><th>Hop path</th><th>Status</th></tr>{info}</table></section>
<section class="wide"><h2>Find Me · people lost in the crowd (<a href="/police">open the police page</a>)</h2><table>
<tr><th>Who</th><th>Status</th><th>Last location</th><th>Note</th><th>Path to server</th><th>Reached server</th></tr>{findme}</table></section>
<section><h2>Gateways online</h2><table><tr><th>Phone</th><th>Owner</th><th>Can reach</th><th>Uploads</th><th>Since</th></tr>{gateways}</table></section>
<section><h2>Users · last path to server</h2><table><tr><th>Handle</th><th>Name</th><th>Last path</th><th>Seen</th></tr>{users}</table></section>
<section class="wide"><h2>All traffic reaching the server (incl. automatic polls &amp; acks)</h2><table>
<tr><th>Time</th><th>From</th><th>Type</th><th>Detail</th><th>Path to server</th><th>Hops</th><th>Receipt</th></tr>{traffic}</table></section>
<section class="wide"><h2>Activity</h2><table>{events}</table></section>
</main></body></html>"""
