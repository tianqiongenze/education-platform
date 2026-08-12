"""L1 Working Memory.

Stores the rolling session summary and current task context per user in Redis
with a 7d TTL. Falls back to an in-memory dict when Redis is unavailable.
"""

from __future__ import annotations

import json
import time
from typing import Any

from .config import MemoryConfig


class WorkingMemory:
    """L1: session summary + task context, Redis-backed with in-memory fallback."""

    def __init__(self, config: MemoryConfig) -> None:
        self.config = config
        self._redis: Any = None
        self._fallback: dict[str, dict] = {}
        if config.redis_url:
            try:
                import redis.asyncio as aioredis  # type: ignore

                self._redis = aioredis.from_url(config.redis_url, decode_responses=True)
            except Exception:
                self._redis = None

    @staticmethod
    def _key(tenant_id: str, user_id: str) -> str:
        return f"mem:work:{tenant_id}:{user_id}"

    def _default_payload(self) -> dict:
        return {"summary": "", "task_context": {}, "last_active": time.time()}

    def _fallback_get(self, user_id: str) -> dict:
        return self._fallback.setdefault(user_id, self._default_payload())

    async def _read(self, tenant_id: str, user_id: str) -> dict:
        key = self._key(tenant_id, user_id)
        if self._redis is not None:
            try:
                raw = await self._redis.get(key)
                if raw:
                    return json.loads(raw)
            except Exception:
                pass
        return self._fallback_get(user_id)

    async def _write(self, tenant_id: str, user_id: str, payload: dict) -> None:
        payload["last_active"] = time.time()
        key = self._key(tenant_id, user_id)
        if self._redis is not None:
            try:
                await self._redis.set(key, json.dumps(payload), ex=self.config.l1_ttl)
                return
            except Exception:
                pass
        self._fallback[user_id] = payload

    async def set_summary(self, summary: str, tenant_id: str, user_id: str) -> None:
        payload = await self._read(tenant_id, user_id)
        payload["summary"] = summary
        await self._write(tenant_id, user_id, payload)

    async def get_summary(self, tenant_id: str, user_id: str) -> str:
        payload = await self._read(tenant_id, user_id)
        return payload.get("summary", "")

    async def set_task_context(self, context: dict, tenant_id: str, user_id: str) -> None:
        payload = await self._read(tenant_id, user_id)
        payload["task_context"] = context
        await self._write(tenant_id, user_id, payload)

    async def get_task_context(self, tenant_id: str, user_id: str) -> dict:
        payload = await self._read(tenant_id, user_id)
        return payload.get("task_context", {})

    async def get_all(self, tenant_id: str, user_id: str) -> dict:
        return await self._read(tenant_id, user_id)

    async def clear(self, tenant_id: str, user_id: str) -> None:
        key = self._key(tenant_id, user_id)
        if self._redis is not None:
            try:
                await self._redis.delete(key)
                return
            except Exception:
                pass
        self._fallback.pop(user_id, None)
