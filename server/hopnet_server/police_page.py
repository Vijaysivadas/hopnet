"""The page police and volunteers open: everyone who used Find Me, on a list and a map.

Static HTML; the browser polls /v1/findme/cases. User-supplied text is only ever set with
textContent, never parsed as HTML.
"""

POLICE_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Find Me · Hopnet</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin="">
<style>
  :root { --bg:#0f1720; --card:#17212c; --line:#22303d; --ink:#e6edf3; --muted:#8b98a5;
          --lost:#f87171; --lost-bg:#3b1414; --found:#2dd4bf; --found-bg:#0b3b37; --accent:#2dd4bf; }
  * { box-sizing:border-box; }
  body { margin:0; font:15px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--ink); }
  header { padding:16px 20px; border-bottom:1px solid var(--line); display:flex; flex-wrap:wrap; gap:8px 16px; align-items:baseline; }
  header h1 { margin:0; font-size:22px; }
  header .sub { color:var(--muted); }
  header a { color:var(--accent); margin-left:auto; }
  .stats { display:flex; gap:8px; flex-wrap:wrap; }
  .stat { background:var(--card); border-radius:999px; padding:4px 12px; font-size:13px; }
  .stat b.lost { color:var(--lost); } .stat b.found { color:var(--found); }
  main { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1.2fr); gap:16px; padding:16px 20px; }
  #list { display:flex; flex-direction:column; gap:12px; min-width:0; }
  .case { background:var(--card); border-radius:12px; padding:14px 16px; border-left:4px solid var(--lost); cursor:pointer; }
  .case.found { border-left-color:var(--found); opacity:.75; }
  .case.selected { outline:2px solid var(--accent); }
  .row { display:flex; gap:8px; align-items:baseline; flex-wrap:wrap; }
  .name { font-size:18px; font-weight:600; }
  .muted { color:var(--muted); } .small { font-size:13px; }
  .pill { font-size:12px; font-weight:700; letter-spacing:.04em; padding:2px 9px; border-radius:999px; }
  .pill.lost { background:var(--lost-bg); color:var(--lost); } .pill.found { background:var(--found-bg); color:var(--found); }
  .when { margin-left:auto; }
  .note { margin:8px 0 0; padding:8px 10px; background:#0f1720; border-radius:8px; }
  .loc { margin-top:8px; font-family:ui-monospace, monospace; font-size:14px; }
  .actions { margin-top:8px; display:flex; gap:12px; flex-wrap:wrap; }
  .actions a { color:var(--accent); }
  .hop { display:inline-block; padding:0 8px; border-radius:999px; background:var(--found-bg); color:var(--accent); font-weight:600; font-size:12px; }
  .arrow { color:var(--muted); padding:0 3px; }
  #mapbox { background:var(--card); border-radius:12px; overflow:hidden; min-height:420px; position:sticky; top:16px; height:calc(100vh - 110px); }
  #map { width:100%; height:100%; }
  #mapnote { padding:16px; color:var(--muted); }
  .empty { background:var(--card); border-radius:12px; padding:24px; color:var(--muted); }
  @media (max-width: 900px) {
    main { grid-template-columns:1fr; padding:12px 16px; }
    #mapbox { order:-1; position:static; height:45vh; min-height:280px; }
    header { padding:12px 16px; }
    header a { margin-left:0; }
  }
</style></head><body>
<header><h1>Find Me</h1><span class="sub">people lost in the crowd · updates every 3 s</span>
<div class="stats"><span class="stat"><b class="lost" id="nlost">0</b> need help</span>
<span class="stat"><b class="found" id="nfound">0</b> found</span>
<span class="stat muted" id="fresh">connecting…</span></div>
<a href="/">server view</a></header>
<main>
  <div id="list"><div class="empty">Loading…</div></div>
  <div id="mapbox"><div id="map"></div><div id="mapnote" hidden>The map needs internet. Coordinates and map links in the list still work.</div></div>
</main>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
<script>
"use strict";
const SANNIDHANAM = [9.4346, 77.0817];
let map = null, markers = null, fitted = false, selected = null, cases = [], lastOk = 0, drawn = "";

function initMap() {
  if (!window.L) { document.getElementById("map").hidden = true; document.getElementById("mapnote").hidden = false; return; }
  map = L.map("map").setView(SANNIDHANAM, 14);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
  markers = L.layerGroup().addTo(map);
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  return e;
}

function ago(ms) {
  if (!ms) return "—";
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return s + " s ago";
  if (s < 3600) return Math.floor(s / 60) + " min ago";
  return Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min ago";
}

function hasFix(c) { return typeof c.lat === "number" && typeof c.lon === "number"; }
function key(c) { return c.address + ":" + c.caseId; }

function pathNode(path) {
  const span = el("span");
  (path || "").split(">").filter(Boolean).forEach((hop, i) => {
    if (i > 0) span.appendChild(el("span", "arrow", "›"));
    span.appendChild(el("span", "hop", hop));
  });
  span.appendChild(el("span", "arrow", "›"));
  span.appendChild(el("span", "hop", "server"));
  return span;
}

function card(c) {
  const lost = c.status === "lost";
  const box = el("div", "case" + (lost ? "" : " found") + (selected === key(c) ? " selected" : ""));
  const top = el("div", "row");
  top.appendChild(el("span", "name", c.name));
  top.appendChild(el("span", "muted small", c.verified ? "@" + c.handle : "unverified"));
  top.appendChild(el("span", "pill " + (lost ? "lost" : "found"), lost ? "NEEDS HELP" : "FOUND"));
  top.appendChild(el("span", "when muted small", "sent " + ago(c.updatedAt)));
  box.appendChild(top);
  if (c.note) box.appendChild(el("div", "note", "“" + c.note + "”"));
  if (hasFix(c)) {
    const acc = typeof c.acc === "number" ? " ±" + Math.round(c.acc) + " m" : "";
    box.appendChild(el("div", "loc", c.lat.toFixed(5) + ", " + c.lon.toFixed(5) + acc));
    if (c.fixAt) box.appendChild(el("div", "muted small", "GPS fix " + ago(c.fixAt)));
    const actions = el("div", "actions");
    const gmaps = el("a", null, "Open in Google Maps");
    gmaps.href = "https://www.google.com/maps/search/?api=1&query=" + c.lat + "," + c.lon;
    gmaps.target = "_blank"; gmaps.rel = "noopener";
    gmaps.addEventListener("click", e => e.stopPropagation());
    actions.appendChild(gmaps);
    box.appendChild(actions);
  } else {
    box.appendChild(el("div", "loc muted", "No GPS location yet"));
  }
  const meta = el("div", "muted small");
  meta.style.marginTop = "8px";
  const bits = [];
  if (typeof c.battery === "number") bits.push("battery " + c.battery + "%");
  bits.push(c.updates + " update" + (c.updates === 1 ? "" : "s"));
  bits.push(c.hops + " hop" + (c.hops === 1 ? "" : "s") + " to reach a phone with internet");
  meta.textContent = bits.join(" · ");
  box.appendChild(meta);
  const via = el("div", "small");
  via.style.marginTop = "6px";
  via.appendChild(pathNode(c.path));
  box.appendChild(via);
  box.addEventListener("click", () => {
    selected = key(c);
    render();
    if (map && hasFix(c)) map.setView([c.lat, c.lon], Math.max(map.getZoom(), 17));
  });
  return box;
}

function popup(c) {
  const box = el("div");
  box.appendChild(el("b", null, c.name));
  box.appendChild(el("div", null, c.status === "lost" ? "Needs help · sent " + ago(c.updatedAt) : "Found"));
  if (c.note) box.appendChild(el("div", null, "“" + c.note + "”"));
  return box;
}

function render() {
  renderList();
  // Only redraw the map when something changed, so an open popup stays open.
  const state = JSON.stringify(cases) + "|" + selected;
  if (state !== drawn) { drawn = state; renderMap(); }
}

function renderList() {
  const list = document.getElementById("list");
  list.replaceChildren();
  const lost = cases.filter(c => c.status === "lost").length;
  document.getElementById("nlost").textContent = lost;
  document.getElementById("nfound").textContent = cases.length - lost;
  if (!cases.length) list.appendChild(el("div", "empty", "Nobody has used Find Me yet. Alerts appear here as soon as any phone near the person has internet."));
  cases.forEach(c => list.appendChild(card(c)));
}

function renderMap() {
  if (!markers) return;
  markers.clearLayers();
  const points = [];
  cases.filter(hasFix).forEach(c => {
    const lostCase = c.status === "lost";
    const color = lostCase ? "#f87171" : "#2dd4bf";
    if (typeof c.acc === "number" && c.acc > 0) {
      L.circle([c.lat, c.lon], { radius: c.acc, color, weight: 1, fillOpacity: 0.08 }).addTo(markers);
    }
    L.circleMarker([c.lat, c.lon], { radius: selected === key(c) ? 11 : 8, color, fillColor: color, fillOpacity: lostCase ? 0.9 : 0.5 })
      .bindPopup(popup(c)).addTo(markers);
    if (lostCase) points.push([c.lat, c.lon]);
  });
  if (!fitted && points.length) {
    map.fitBounds(points, { maxZoom: 17, padding: [40, 40] });
    fitted = true;
  }
}

async function refresh() {
  try {
    const res = await fetch("/v1/findme/cases", { cache: "no-store" });
    if (!res.ok) throw new Error(res.status);
    cases = await res.json();
    lastOk = Date.now();
    render();
  } catch (e) { /* keep showing the last data */ }
  document.getElementById("fresh").textContent = lastOk ? "updated " + ago(lastOk) : "server unreachable";
}

initMap();
refresh();
setInterval(refresh, 3000);
</script></body></html>
"""
