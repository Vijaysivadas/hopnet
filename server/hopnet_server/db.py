"""SQLite persistence: users, processed uploads (idempotency), mailbox, Find Me cases, Live Info
lookups, links and watches, event log."""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    handle TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sign_pk BLOB NOT NULL,
    enc_pk BLOB NOT NULL,
    address TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS uploads (
    msg_id TEXT PRIMARY KEY,
    sender TEXT,
    svc TEXT,
    kind TEXT,
    status INTEGER NOT NULL,
    hops INTEGER NOT NULL,
    trace TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    receipt BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS mailbox (
    msg_id TEXT PRIMARY KEY,
    recipient TEXT NOT NULL,
    svc TEXT NOT NULL,
    kind TEXT NOT NULL,
    ref TEXT,
    origin TEXT,
    envelope BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    delivered_at INTEGER
);
CREATE INDEX IF NOT EXISTS mailbox_pending ON mailbox (recipient, delivered_at);
-- Find Me: one row per person per incident, holding their latest report.
CREATE TABLE IF NOT EXISTS findme_cases (
    address TEXT NOT NULL,
    case_id TEXT NOT NULL,
    handle TEXT,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    lat REAL,
    lon REAL,
    acc REAL,
    fix_at INTEGER,
    note TEXT,
    battery INTEGER,
    first_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    hops INTEGER NOT NULL,
    path TEXT NOT NULL,
    gateway TEXT,
    updates INTEGER NOT NULL,
    PRIMARY KEY (address, case_id)
);
-- Live Info: every question a phone asked, and what answering it cost.
CREATE TABLE IF NOT EXISTS info_queries (
    msg_id TEXT PRIMARY KEY,
    address TEXT NOT NULL,
    tool TEXT NOT NULL,
    query TEXT NOT NULL,
    asked_at INTEGER NOT NULL,
    answered_at INTEGER,
    title TEXT,
    ok INTEGER,
    level TEXT,
    fetched INTEGER,
    wire INTEGER,
    cached INTEGER,
    ms INTEGER,
    down_id TEXT,
    sources TEXT
);
-- Short keys standing in for long URLs inside answer cards.
CREATE TABLE IF NOT EXISTS info_links (
    key TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    title TEXT,
    created_at INTEGER NOT NULL
);
-- "Tell me when a new alert is issued here": one per person per topic.
CREATE TABLE IF NOT EXISTS info_watches (
    address TEXT NOT NULL,
    topic TEXT NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    place TEXT,
    created_at INTEGER NOT NULL,
    until INTEGER NOT NULL,
    seen TEXT NOT NULL,
    checked_at INTEGER,
    sent INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (address, topic)
);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at INTEGER NOT NULL,
    kind TEXT NOT NULL,
    text TEXT NOT NULL
);
"""

# Columns added after the first release; applied to new and existing databases alike.
MIGRATIONS = [
    ("uploads", "summary", "TEXT"),          # short human description set by the service
    ("uploads", "peer", "TEXT"),             # e.g. chat recipient handle
    ("uploads", "note", "TEXT"),             # e.g. "no such user"
    ("mailbox", "pushed_via", "TEXT"),       # gateway labels the DOWN was handed to, comma-separated
    ("mailbox", "delivered_path", "TEXT"),   # path reported by the recipient, e.g. cloud>G2>R
    ("mailbox", "delivered_hops", "INTEGER"),
]


@dataclass
class User:
    handle: str
    name: str
    sign_pk: bytes
    enc_pk: bytes
    address: str


@dataclass
class Upload:
    msg_id: str
    sender: str | None
    status: int
    receipt: bytes


@dataclass
class MailItem:
    msg_id: str
    recipient: str
    svc: str
    kind: str
    ref: str | None
    origin: str | None
    envelope: bytes
    created_at: int
    expires_at: int
    delivered_at: int | None


@dataclass
class FindMeReport:
    """One Find Me broadcast as the server received it. Times are the phone's clock, except received_at."""
    address: str
    case_id: str
    handle: str | None
    name: str
    status: str
    lat: float | None
    lon: float | None
    acc: float | None
    fix_at: int | None
    note: str | None
    battery: int | None
    sent_at: int
    hops: int
    path: str
    gateway: str | None


class Database:
    def __init__(self, path: Path | str):
        if isinstance(path, Path):
            path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._conn.executescript(SCHEMA)
            for table, column, kind in MIGRATIONS:
                existing = {row[1] for row in self._conn.execute(f"PRAGMA table_info({table})")}
                if column not in existing:
                    self._conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {kind}")
            self._conn.commit()

    def _exec(self, sql: str, args: tuple = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, args)
            self._conn.commit()
            return cur

    def _all(self, sql: str, args: tuple = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(sql, args).fetchall()

    # ---- users -------------------------------------------------------------------------

    def upsert_user(self, user: User, now: int) -> None:
        with self._lock:
            # Same key under a new handle: the device re-registered, drop the old handle.
            self._conn.execute("DELETE FROM users WHERE address = ? AND handle != ?", (user.address, user.handle))
            self._conn.execute(
                "INSERT INTO users (handle, name, sign_pk, enc_pk, address, created_at) VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(handle) DO UPDATE SET name = excluded.name, enc_pk = excluded.enc_pk",
                (user.handle, user.name, user.sign_pk, user.enc_pk, user.address, now),
            )
            self._conn.commit()

    @staticmethod
    def _user(row: sqlite3.Row | None) -> User | None:
        if row is None:
            return None
        return User(row["handle"], row["name"], bytes(row["sign_pk"]), bytes(row["enc_pk"]), row["address"])

    def user_by_handle(self, handle: str) -> User | None:
        rows = self._all("SELECT * FROM users WHERE handle = ?", (handle.lower(),))
        return self._user(rows[0] if rows else None)

    def user_by_address(self, address: str) -> User | None:
        rows = self._all("SELECT * FROM users WHERE address = ?", (address,))
        return self._user(rows[0] if rows else None)

    def users(self) -> list[User]:
        return [self._user(r) for r in self._all("SELECT * FROM users ORDER BY created_at")]

    # ---- uploads -----------------------------------------------------------------------

    def get_upload(self, msg_id: str) -> Upload | None:
        rows = self._all("SELECT msg_id, sender, status, receipt FROM uploads WHERE msg_id = ?", (msg_id,))
        if not rows:
            return None
        r = rows[0]
        return Upload(r["msg_id"], r["sender"], r["status"], bytes(r["receipt"]))

    def record_upload(self, msg_id: str, sender: str | None, svc: str | None, kind: str | None,
                      status: int, hops: int, trace: str, now: int, receipt: bytes) -> None:
        self._exec(
            "INSERT OR IGNORE INTO uploads (msg_id, sender, svc, kind, status, hops, trace, received_at, receipt) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (msg_id, sender, svc, kind, status, hops, trace, now, receipt),
        )

    def annotate_upload(self, msg_id: str, summary: str | None = None, peer: str | None = None,
                        note: str | None = None) -> None:
        self._exec(
            "UPDATE uploads SET summary = COALESCE(?, summary), peer = COALESCE(?, peer), note = COALESCE(?, note) "
            "WHERE msg_id = ?",
            (summary, peer, note, msg_id),
        )

    def recent_uploads(self, limit: int = 60) -> list[sqlite3.Row]:
        return self._all(
            "SELECT u.*, s.handle AS sender_handle FROM uploads u LEFT JOIN users s ON s.address = u.sender "
            "ORDER BY u.received_at DESC LIMIT ?",
            (limit,),
        )

    def journeys(self, svc: str, kind: str, limit: int = 40) -> list[sqlite3.Row]:
        """Each request of svc/kind with the DOWN it produced: path up, push gateways, path down."""
        return self._all(
            "SELECT u.msg_id, u.received_at, u.hops AS up_hops, u.trace AS up_path, u.status, u.summary, u.peer, u.note, "
            "       s.handle AS sender_handle, m.msg_id AS down_id, r.handle AS recipient_handle, m.pushed_via, "
            "       m.delivered_path, m.delivered_hops, m.delivered_at "
            "FROM uploads u "
            "LEFT JOIN users s ON s.address = u.sender "
            "LEFT JOIN mailbox m ON m.ref = u.msg_id AND m.svc = u.svc AND m.kind = u.kind "
            "LEFT JOIN users r ON r.address = m.recipient "
            "WHERE u.svc = ? AND u.kind = ? ORDER BY u.received_at DESC LIMIT ?",
            (svc, kind, limit),
        )

    # ---- mailbox -----------------------------------------------------------------------

    def mailbox_insert(self, item: MailItem) -> None:
        self._exec(
            "INSERT INTO mailbox (msg_id, recipient, svc, kind, ref, origin, envelope, created_at, expires_at, delivered_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
            (item.msg_id, item.recipient, item.svc, item.kind, item.ref, item.origin, item.envelope,
             item.created_at, item.expires_at),
        )

    @staticmethod
    def _mail(row: sqlite3.Row) -> MailItem:
        return MailItem(row["msg_id"], row["recipient"], row["svc"], row["kind"], row["ref"], row["origin"],
                        bytes(row["envelope"]), row["created_at"], row["expires_at"], row["delivered_at"])

    def mailbox_get(self, msg_id: str) -> MailItem | None:
        rows = self._all("SELECT * FROM mailbox WHERE msg_id = ?", (msg_id,))
        return self._mail(rows[0]) if rows else None

    def pending_for(self, recipient: str, now: int) -> list[MailItem]:
        rows = self._all(
            "SELECT * FROM mailbox WHERE recipient = ? AND delivered_at IS NULL AND expires_at > ? ORDER BY created_at",
            (recipient, now),
        )
        return [self._mail(r) for r in rows]

    def mark_delivered(self, msg_id: str, now: int, path: str | None = None, hops: int | None = None) -> None:
        self._exec(
            "UPDATE mailbox SET delivered_at = COALESCE(delivered_at, ?), "
            "delivered_path = COALESCE(?, delivered_path), delivered_hops = COALESCE(?, delivered_hops) WHERE msg_id = ?",
            (now, path, hops, msg_id),
        )

    def mailbox_note_push(self, msg_id: str, gateway_label: str) -> None:
        with self._lock:
            row = self._conn.execute("SELECT pushed_via FROM mailbox WHERE msg_id = ?", (msg_id,)).fetchone()
            if row is None:
                return
            labels = [x for x in (row["pushed_via"] or "").split(",") if x]
            if gateway_label not in labels:
                labels.append(gateway_label)
                self._conn.execute("UPDATE mailbox SET pushed_via = ? WHERE msg_id = ?", (",".join(labels), msg_id))
                self._conn.commit()

    def recent_mail(self, limit: int = 30) -> list[MailItem]:
        return [self._mail(r) for r in self._all("SELECT * FROM mailbox ORDER BY created_at DESC LIMIT ?", (limit,))]

    # ---- Find Me -----------------------------------------------------------------------

    def findme_report(self, r: FindMeReport, now: int) -> bool:
        """Merges a report into its case. Returns True if it is now the case's latest report.
        A report without a GPS fix keeps the last known location; an older report only counts."""
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM findme_cases WHERE address = ? AND case_id = ?", (r.address, r.case_id)
            ).fetchone()
            if row is None:
                self._conn.execute(
                    "INSERT INTO findme_cases (address, case_id, handle, name, status, lat, lon, acc, fix_at, note, "
                    "battery, first_at, updated_at, received_at, hops, path, gateway, updates) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    (r.address, r.case_id, r.handle, r.name, r.status, r.lat, r.lon, r.acc, r.fix_at, r.note,
                     r.battery, r.sent_at, r.sent_at, now, r.hops, r.path, r.gateway),
                )
                self._conn.commit()
                return True
            if r.sent_at < row["updated_at"]:
                self._conn.execute(
                    "UPDATE findme_cases SET updates = updates + 1, first_at = MIN(first_at, ?) "
                    "WHERE address = ? AND case_id = ?",
                    (r.sent_at, r.address, r.case_id),
                )
                self._conn.commit()
                return False
            has_fix = r.lat is not None and r.lon is not None
            self._conn.execute(
                "UPDATE findme_cases SET handle = COALESCE(?, handle), name = ?, status = ?, lat = ?, lon = ?, acc = ?, "
                "fix_at = ?, note = ?, battery = ?, updated_at = ?, received_at = ?, hops = ?, path = ?, gateway = ?, "
                "updates = updates + 1 WHERE address = ? AND case_id = ?",
                (r.handle, r.name, r.status,
                 r.lat if has_fix else row["lat"], r.lon if has_fix else row["lon"],
                 r.acc if has_fix else row["acc"], r.fix_at if has_fix else row["fix_at"],
                 r.note if r.note is not None else row["note"],
                 r.battery if r.battery is not None else row["battery"],
                 r.sent_at, now, r.hops, r.path, r.gateway, r.address, r.case_id),
            )
            self._conn.commit()
            return True

    def findme_cases(self, limit: int = 200) -> list[sqlite3.Row]:
        """Lost people first, then the most recently updated."""
        return self._all(
            "SELECT * FROM findme_cases ORDER BY CASE WHEN status = 'lost' THEN 0 ELSE 1 END, updated_at DESC LIMIT ?",
            (limit,),
        )

    # ---- Live Info ---------------------------------------------------------------------

    def info_asked(self, msg_id: str, address: str, tool: str, query: str, now: int) -> None:
        self._exec(
            "INSERT OR IGNORE INTO info_queries (msg_id, address, tool, query, asked_at) VALUES (?, ?, ?, ?, ?)",
            (msg_id, address, tool, query, now),
        )

    def info_answered(self, msg_id: str, title: str, ok: bool, level: str, fetched: int, wire: int, cached: bool,
                      ms: int, down_id: str | None, sources: str, now: int) -> None:
        self._exec(
            "UPDATE info_queries SET answered_at = ?, title = ?, ok = ?, level = ?, fetched = ?, wire = ?, cached = ?, "
            "ms = ?, down_id = ?, sources = ? WHERE msg_id = ?",
            (now, title, int(ok), level, fetched, wire, int(cached), ms, down_id, sources, msg_id),
        )

    def info_recent(self, limit: int = 30) -> list[sqlite3.Row]:
        """Each question with its upload path and how (and whether) the answer got back."""
        return self._all(
            "SELECT q.*, s.handle AS handle, u.trace AS up_path, u.hops AS up_hops, "
            "       m.pushed_via, m.delivered_at, m.delivered_path, m.delivered_hops "
            "FROM info_queries q "
            "LEFT JOIN users s ON s.address = q.address "
            "LEFT JOIN uploads u ON u.msg_id = q.msg_id "
            "LEFT JOIN mailbox m ON m.msg_id = q.down_id "
            "ORDER BY q.asked_at DESC LIMIT ?",
            (limit,),
        )

    def info_totals(self) -> dict:
        rows = self._all(
            "SELECT COUNT(*) AS n, COALESCE(SUM(fetched), 0) AS fetched, COALESCE(SUM(wire), 0) AS wire "
            "FROM info_queries WHERE answered_at IS NOT NULL"
        )
        r = rows[0]
        return {"answered": r["n"], "fetched": r["fetched"], "wire": r["wire"]}

    def info_link_put(self, key: str, url: str, title: str, now: int) -> None:
        self._exec(
            "INSERT INTO info_links (key, url, title, created_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET title = COALESCE(NULLIF(excluded.title, ''), info_links.title)",
            (key, url, title, now),
        )

    def info_link_get(self, key: str) -> tuple[str, str] | None:
        rows = self._all("SELECT url, title FROM info_links WHERE key = ?", (key,))
        return (rows[0]["url"], rows[0]["title"] or "") if rows else None

    def info_watch_put(self, address: str, topic: str, lat: float, lon: float, place: str | None,
                       until: int, seen: str, now: int) -> None:
        self._exec(
            "INSERT INTO info_watches (address, topic, lat, lon, place, created_at, until, seen, checked_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(address, topic) DO UPDATE SET lat = excluded.lat, "
            "lon = excluded.lon, place = excluded.place, until = excluded.until, seen = excluded.seen, "
            "checked_at = excluded.checked_at",
            (address, topic, lat, lon, place, now, until, seen, now),
        )

    def info_watch_delete(self, address: str, topic: str) -> bool:
        return self._exec("DELETE FROM info_watches WHERE address = ? AND topic = ?", (address, topic)).rowcount > 0

    def info_watches(self) -> list[sqlite3.Row]:
        return self._all("SELECT * FROM info_watches ORDER BY created_at")

    def info_watch_checked(self, address: str, topic: str, seen: str, now: int, sent: int = 0) -> None:
        self._exec(
            "UPDATE info_watches SET seen = ?, checked_at = ?, sent = sent + ? WHERE address = ? AND topic = ?",
            (seen, now, sent, address, topic),
        )

    # ---- events (debug page) -----------------------------------------------------------

    def event(self, kind: str, text: str, now: int) -> None:
        self._exec("INSERT INTO events (at, kind, text) VALUES (?, ?, ?)", (now, kind, text))

    def recent_events(self, limit: int = 60) -> list[sqlite3.Row]:
        return self._all("SELECT at, kind, text FROM events ORDER BY id DESC LIMIT ?", (limit,))
