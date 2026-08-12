"""SAD §5 Layer 1 — Semantic cache backed by Redis.

Caches a ChatResponse keyed by a sha256 hash of the last user message
content (the prompt). A cache hit short-circuits the entire cost stack —
routing, cascade and fallback are skipped (SAD §5, irreversible order).

Redis is optional: when the cluster is unavailable the cache degrades
gracefully to a no-op (get returns None, put is a skip), so the gateway
keeps serving traffic.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from typing import TYPE_CHECKING

import structlog

from ..models import ChatMessage, ChatResponse

if TYPE_CHECKING:
    from redis import Redis

logger = structlog.get_logger(__name__)

_CACHE_PREFIX = "ep:llm:cache:"
_CACHE_TTL_SEC = 3600


def _build_redis_client() -> "Redis | None":
    """Build a Redis client from env, or return None if Redis is unavailable.

    REDIS_ADDR default is ``redis-cluster:6379`` (in-cluster DNS). Any
    connection/import failure degrades to no-cache so the gateway stays up.
    """
    addr = os.environ.get("REDIS_ADDR", "redis-cluster:6379")
    try:
        import redis  # local import — keeps the module importable without redis installed

        host, _, port = addr.partition(":")
        client = redis.Redis(
            host=host or "redis-cluster",
            port=int(port) if port else 6379,
            decode_responses=True,
            socket_connect_timeout=0.5,
            socket_timeout=0.5,
        )
        # Cheap ping to validate the connection eagerly. If it fails we log
        # once and fall back to no-cache; subsequent get/put are no-ops.
        client.ping()
        return client
    except Exception as exc:  # noqa: BLE001 — best-effort, never fatal
        logger.warning("semantic_cache: redis unavailable, degrading to no-cache", addr=addr, error=str(exc))
        return None


class SemanticCache:
    """Layer 1 — semantic cache keyed by sha256(prompt).

    The cache value is the JSON-serialized ``ChatResponse`` with a 1h TTL.
    """

    def __init__(self, client: "Redis | None" | None = None) -> None:
        # Allow injecting a fake/mocked redis client (tests). When None is
        # passed we attempt to build one from the environment.
        self._client: "Redis | None" = client if client is not None else _build_redis_client()

    # ------------------------------------------------------------------ utils

    @staticmethod
    def hash(messages: list[ChatMessage]) -> str:
        """Return the hex sha256 digest of the last user message content.

        Falls back to the empty-string hash when there is no user message,
        so the method is total (never raises on empty input).
        """
        prompt = ""
        for msg in reversed(messages):
            if msg.role == "user":
                prompt = msg.content
                break
        digest = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
        return digest

    @property
    def available(self) -> bool:
        """True when a live Redis client is wired in (cache is active)."""
        return self._client is not None

    # ------------------------------------------------------------------ public

    def get(self, messages: list[ChatMessage]) -> ChatResponse | None:
        """Return a cached response for ``messages`` or None on miss/no-Redis."""
        if self._client is None:
            return None
        key = _CACHE_PREFIX + self.hash(messages)
        try:
            raw = self._client.get(key)
        except Exception as exc:  # noqa: BLE001 — Redis must never break a request
            logger.warning("semantic_cache.get: redis error, treating as miss", error=str(exc))
            return None
        if raw is None:
            return None
        try:
            return ChatResponse.model_validate_json(raw)
        except Exception as exc:  # noqa: BLE001 — corrupt cache entry → treat as miss
            logger.warning("semantic_cache.get: corrupt entry, evicting", key=key, error=str(exc))
            try:
                self._client.delete(key)
            except Exception:  # noqa: BLE001 — best-effort eviction
                pass
            return None

    def put(self, messages: list[ChatMessage], response: ChatResponse) -> None:
        """Store ``response`` under the prompt hash with a 1h TTL."""
        if self._client is None:
            return
        key = _CACHE_PREFIX + self.hash(messages)
        try:
            payload = response.model_dump_json()
            self._client.set(key, payload, ex=_CACHE_TTL_SEC)
        except Exception as exc:  # noqa: BLE001 — cache write failures are non-fatal
            logger.warning("semantic_cache.put: redis error, skipping store", error=str(exc))
