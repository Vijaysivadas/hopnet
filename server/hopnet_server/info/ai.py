"""Optional AI answers via the Claude API, grounded in a live web search. Enabled when the server has
ANTHROPIC_API_KEY; model from HOPNET_AI_MODEL. If the account can't use the server-side web search
tool, the answer is grounded in our own search results instead."""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import TYPE_CHECKING

from .fetch import FetchError

if TYPE_CHECKING:
    from .env import Env
    from .web import Result

API = "https://api.anthropic.com/v1/messages"
DEFAULT_MODEL = "claude-haiku-5-5"
MAX_ANSWER_BYTES = 1200

SYSTEM = (
    "You answer questions for people whose phones have no internet. Your answer reaches them hop by hop "
    "over a Bluetooth mesh of strangers' phones, so every byte counts. Use web search for anything current. "
    "Reply in at most 600 characters of plain text: no markdown, no lists, no links, no preamble. Lead with "
    "the direct answer, then the one or two facts that matter most (dates, numbers, places, what to do). "
    "If it is about safety or an emergency, put the safe action first. If information may be outdated or "
    "sources disagree, say so in a few words. Reply in the language of the question."
)


@dataclass
class Answer:
    text: str
    sources: list["Result"]
    engine: str


class AI:
    def __init__(self, api_key: str | None, model: str = DEFAULT_MODEL, use_web_search: bool = True):
        self.api_key = api_key
        self.model = model
        self.use_web_search = use_web_search

    @classmethod
    def from_env(cls) -> "AI":
        return cls(os.environ.get("ANTHROPIC_API_KEY"), os.environ.get("HOPNET_AI_MODEL") or DEFAULT_MODEL)

    @property
    def enabled(self) -> bool:
        return bool(self.api_key)

    async def answer(self, env: "Env", question: str) -> Answer:
        from .web import web_search

        if self.use_web_search:
            try:
                content = await self._call(env, [{"role": "user", "content": question}], tools=True)
                text, sources = _read(content)
                if text:
                    return Answer(_trim(text), sources, f"Claude ({self.model}) + web search")
            except FetchError:
                pass
        # Ground the answer in our own search results.
        results, engine = await web_search(env, question, limit=5)
        context = "\n".join(f"{i + 1}. {r.title} ({r.url}): {r.snippet}" for i, r in enumerate(results))
        prompt = f"Web search results from {engine}:\n{context}\n\nQuestion: {question}"
        text, _ = _read(await self._call(env, [{"role": "user", "content": prompt}], tools=False))
        if not text:
            raise FetchError("The AI gave no answer")
        return Answer(_trim(text), results, f"Claude ({self.model}) + {engine}")

    async def _call(self, env: "Env", messages: list[dict], tools: bool) -> list[dict]:
        body: dict = {"model": self.model, "max_tokens": 700, "system": SYSTEM, "messages": messages}
        if tools:
            body["tools"] = [{"type": "web_search_20250305", "name": "web_search", "max_uses": 3,
                              "user_location": {"type": "approximate", "country": "IN"}}]
        headers = {"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"}
        content: list[dict] = []
        for _ in range(3):  # server tools may pause a long turn; continue it
            f = await env.fetcher.get(API, method="POST", json_body=body, headers=headers, ttl=1800, timeout=60,
                                      meter=env.meter)
            data = f.json()
            if f.status != 200 or not isinstance(data, dict):
                message = ((data or {}).get("error") or {}).get("message", "") if isinstance(data, dict) else ""
                raise FetchError(f"AI service error {f.status} {message}".strip())
            content += data.get("content") or []
            if data.get("stop_reason") != "pause_turn":
                break
            body = {**body, "messages": messages + [{"role": "assistant", "content": content}]}
        return content


def _read(content: list[dict]) -> tuple[str, list["Result"]]:
    """The answer text, and its sources: cited pages first, then other search results."""
    from .web import Result

    texts, cited, searched = [], [], []
    for block in content:
        if block.get("type") == "text":
            texts.append(block.get("text", ""))
            for c in block.get("citations") or []:
                if c.get("url"):
                    cited.append(Result(c.get("title") or c["url"], c["url"], c.get("cited_text", "")))
        elif block.get("type") == "web_search_tool_result" and isinstance(block.get("content"), list):
            for r in block["content"]:
                if r.get("url"):
                    searched.append(Result(r.get("title") or r["url"], r["url"], ""))
    sources, seen = [], set()
    for r in cited + searched:
        if r.url not in seen:
            seen.add(r.url)
            sources.append(r)
    return " ".join(t.strip() for t in texts if t.strip()), sources


def _trim(text: str) -> str:
    from .card import cut_bytes
    return cut_bytes(" ".join(text.split()), MAX_ANSWER_BYTES)
