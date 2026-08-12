"""L0 Immediate Memory.

Stores the most recent conversation turns in Redis with a 24h TTL. When the
window overflows, the oldest messages are summarized and pushed to L1 working
memory. Falls back to an in-memory dict when Redis is unavailable.
"""

from __future__ import annotations

import json
import time
from typing import Any

from .config import MemoryConfig


class ImmediateMemory:
    """L0: recent conversation turns, Redis-backed with in-memory fallback."""

    def __init__(self, config: MemoryConfig) -> None:
        self.config = config
        self._redis: Any = None
        self._fallback: dict[str, list[dict]] = {}
        if config.redis_url:
            try:
                import redis.asyncio as aioredis  # type: ignore

                self._redis = aioredis.from_url(config.redis_url, decode_responses=True)
            except Exception:
                self._redis = None

    @staticmethod
    def _key(tenant_id: str, session_id: str) -> str:
        return f"mem:imm:{tenant_id}:{session_id}"

    def _fallback_get(self, session_id: str) -> list[dict]:
        return self._fallback.setdefault(session_id, [])

    async def add(self, message: dict, tenant_id: str, session_id: str) -> list[dict]:
        """Append a message to L0. Returns the overflowed (evicted) messages
        when the window exceeds l0_max_messages. Caller is responsible for
        summarizing overflow into L1."""
        key = self._key(tenant_id, session_id)
        # Enrich message with timestamp
        msg = dict(message)
        msg.setdefault("ts", time.time())

        if self._redis is not None:
            try:
                await self._redis.rpush(key, json.dumps(msg))
                await self._redis.expire(key, self.config.l0_ttl)
                raw = await self._redis.lrange(key, 0, -1)
                messages = [json.loads(r) for r in raw]
            except Exception:
                messages = self._fallback_get(session_id)
                messages.append(msg)
        else:
            messages = self._fallback_get(session_id)
            messages.append(msg)

        overflow: list[dict] = []
        max_msgs = self.config.l0_max_messages
        while len(messages) > max_msgs:
            overflow.append(messages.pop(0))

        if self._redis is not None and overflow:
            try:
                # Rebuild the Redis list without overflowed items
                await self._redis.delete(key)
                if messages:
                    await self._redis.rpush(key, *[json.dumps(m) for m in messages])
                    await self._redis.expire(key, self.config.l0_ttl)
            except Exception:
                pass
        return overflow

    async def get(self, tenant_id: str, session_id: str) -> list[dict]:
        """Return the most recent up to l0_max_messages messages."""
        key = self._key(tenant_id, session_id)
        if self._redis is not None:
            try:
                raw = await self._redis.lrange(key, 0, -1)
                return [json.loads(r) for r in raw]
            except Exception:
                pass
        return list(self._fallback.get(session_id, []))

    async def clear(self, tenant_id: str, session_id: str) -> None:
        key = self._key(tenant_id, session_id)
        if self._redis is not None:
            try:
                await self._redis.delete(key)
                return
            except Exception:
                pass
        self._fallback.pop(session_id, None)

    def summarize(self, messages: list[dict]) -> str:
        """Mock LLM summarization. In production this delegates to the SP04
        gateway LLM. Returns a deterministic placeholder summary."""
        n = len(messages)
        roles = [m.get("role", "?") for m in messages]
        user_turns = roles.count("user")
        return f"Summary of {n} messages ({user_turns} user turns)."
