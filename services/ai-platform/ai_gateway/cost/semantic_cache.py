"""SAD §5 Layer 1 — Semantic cache backed by Redis.

Caches a ChatResponse keyed by a sha256 hash of the last user message
content (the prompt). A cache hit short-circuits the entire cost stack —
routing, cascade and fallback are skipped (SAD §5, irreversible order).

The semantic hashing layer (SHA256 of the prompt) is retained, but the
underlying get/set/evict is delegated to :class:`ep_common.cache.CacheService`,
the unified L1+L2 cache shared with the Java ``ep-cache`` SDK and the Go
``ep-common-go`` SDK. ``CacheService`` provides an in-process L1, an L2
backed by Redis, and Pub/Sub broadcast invalidation on the
``ep:cache:invalidate`` channel so cache writes/evictions are coherent
across the fleet.

Redis is optional: when the cluster is unavailable the cache degrades
gracefully to L1-only (no L2, no broadcast), so the gateway keeps serving
traffic.
"""

from __future__ import annotations

import hashlib
import logging
import os
from typing import TYPE_CHECKING

import structlog

from ep_common.cache import CacheService
from ..models import ChatMessage, ChatResponse

if TYPE_CHECKING:
    from redis import Redis

logger = structlog.get_logger(__name__)

_CACHE_NAME = "ep:llm:cache"
# Backwards-compat: the previous key layout was ``ep:llm:cache:<hash>``. The
# unified CacheService builds full keys as ``"{cache_name}:{key}"``, so the
# effective on-the-wire key is still ``ep:llm:cache:<hash>``. We keep
# ``_CACHE_PREFIX`` exported for tests that assert against the wire format.
_CACHE_PREFIX = _CACHE_NAME + ":"
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
        # once and fall back to L1-only; subsequent get/put degrade gracefully.
        client.ping()
        return client
    except Exception as exc:  # noqa: BLE001 — best-effort, never fatal
        logger.warning("semantic_cache: redis unavailable, degrading to L1-only", addr=addr, error=str(exc))
        return None


class SemanticCache:
    """Layer 1 — semantic cache keyed by sha256(prompt).

    The cache value is the JSON-serialized ``ChatResponse`` with a 1h TTL,
    stored via the unified :class:`CacheService` (L1 + L2 + Pub/Sub).
    """

    def __init__(self, client: "Redis | None" | None = None) -> None:
        # Allow injecting a fake/mocked redis client (tests). When None is
        # passed we attempt to build one from the environment.
        redis_client: "Redis | None" = client if client is not None else _build_redis_client()
        # CacheService handles the None case as L1-only dev mode.
        self._cache = CacheService(redis_client=redis_client)

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
        """True when an L2 (Redis) is wired in (cache is active across fleet)."""
        return self._cache._redis is not None  # noqa: SLF001 — introspection for tests/ops

    # ------------------------------------------------------------------ public

    def get(self, messages: list[ChatMessage]) -> ChatResponse | None:
        """Return a cached response for ``messages`` or None on miss/no-Redis."""
        key = self.hash(messages)
        try:
            raw = self._cache.get_or_load(
                _CACHE_NAME,
                key,
                loader=lambda: None,
                ttl_sec=_CACHE_TTL_SEC,
                cache_empty=False,
            )
        except Exception as exc:  # noqa: BLE001 — Redis must never break a request
            logger.warning("semantic_cache.get: cache error, treating as miss", error=str(exc))
            return None
        if raw is None:
            return None
        try:
            return ChatResponse.model_validate_json(raw)
        except Exception as exc:  # noqa: BLE001 — corrupt cache entry → evict and treat as miss
            logger.warning("semantic_cache.get: corrupt entry, evicting", key=key, error=str(exc))
            try:
                self._cache.evict(_CACHE_NAME, key)
            except Exception:  # noqa: BLE001 — best-effort eviction
                pass
            return None

    def put(self, messages: list[ChatMessage], response: ChatResponse) -> None:
        """Store ``response`` under the prompt hash with a 1h TTL."""
        key = self.hash(messages)
        payload = response.model_dump_json()
        try:
            self._cache.put(_CACHE_NAME, key, payload, ttl_sec=_CACHE_TTL_SEC)
        except Exception as exc:  # noqa: BLE001 — cache write failures are non-fatal
            logger.warning("semantic_cache.put: cache error, skipping store", error=str(exc))
