"""Unified L1+L2 cache with Pub/Sub broadcast (D-110 extension).

Mirrors the Java ``ep-cache`` SDK semantics:

- **L1** — in-process TTL dict (custom, thread-safe via ``threading.Lock``).
  On hit the value is returned without touching Redis.
- **L2** — ``redis.Redis`` instance. On L1 miss we consult L2; on L2 hit we
  backfill L1.
- **Pub/Sub** — a daemon background thread subscribes to the
  ``ep:cache:invalidate`` channel. Any eviction broadcast by another process
  (Java, Go, or another Python instance) evicts the matching L1 entry, so L1
  never serves stale data across the fleet.

The sentinel value ``EMPTY_SENTINEL = "__EP_CACHE_EMPTY__"`` is stored in L2
when the loader returns ``None`` and ``cache_empty=True``, so repeated lookups
of a known-empty result do not hammer the loader — identical to the Java SDK.

Graceful degradation: when no ``redis_client`` is supplied (dev mode) the
service operates as an L1-only cache with no broadcast. ``redis`` is imported
lazily so the package remains importable without the dependency installed
(the same pattern used by ``ai_platform.semantic_cache``).
"""

from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)

# Cross-language interoperability constants — MUST match Java/Go SDK exactly.
EMPTY_SENTINEL = "__EP_CACHE_EMPTY__"
INVALIDATE_CHANNEL = "ep:cache:invalidate"


class CacheService:
    """Two-tier cache with Pub/Sub invalidation.

    Thread-safe. Safe to share as a process-wide singleton.

    Parameters
    ----------
    redis_client:
        Optional ``redis.Redis`` instance. When ``None`` (dev mode) L2 and
        Pub/Sub are disabled — only L1 is used.
    l1_maxsize:
        Soft cap on the L1 dict size. When exceeded the oldest entries are
        evicted (FIFO by insertion order, mirroring the practical behaviour
        of a bounded L1).
    """

    EMPTY_SENTINEL = EMPTY_SENTINEL  # re-exported on the class for convenience

    def __init__(
        self,
        redis_client: Optional[Any] = None,
        l1_maxsize: int = 4096,
    ) -> None:
        self._redis = redis_client
        self._l1_maxsize = max(1, int(l1_maxsize))
        # L1 entry: full_key -> (value, expiry_monotonic_or_None)
        self._l1: dict[str, tuple[Any, Optional[float]]] = {}
        self._lock = threading.Lock()
        self._pubsub_thread: Optional[threading.Thread] = None
        self._pubsub_stop = threading.Event()

        if self._redis is not None:
            self._start_pubsub_thread()

    # ------------------------------------------------------------------ L1 utils

    def _l1_get(self, full_key: str) -> tuple[bool, Any]:
        """Return ``(hit, value)`` from L1. Evicts expired entries inline."""
        with self._lock:
            entry = self._l1.get(full_key)
            if entry is None:
                return False, None
            value, expiry = entry
            if expiry is not None and expiry <= time.monotonic():
                # Expired — evict and report miss.
                self._l1.pop(full_key, None)
                return False, None
            return True, value

    def _l1_put(self, full_key: str, value: Any, ttl_sec: int) -> None:
        expiry = time.monotonic() + ttl_sec if ttl_sec and ttl_sec > 0 else None
        with self._lock:
            self._l1[full_key] = (value, expiry)
            self._enforce_l1_maxsize()

    def _l1_evict(self, full_key: str) -> None:
        with self._lock:
            self._l1.pop(full_key, None)

    def _enforce_l1_maxsize(self) -> None:
        """Caller MUST hold ``self._lock``."""
        if len(self._l1) <= self._l1_maxsize:
            return
        # FIFO eviction: drop the oldest inserted key. dict preserves insertion
        # order in Python 3.7+, so ``next(iter(...))`` is the oldest entry.
        while len(self._l1) > self._l1_maxsize:
            oldest = next(iter(self._l1))
            self._l1.pop(oldest, None)

    @staticmethod
    def _full_key(cache_name: str, key: str) -> str:
        return f"{cache_name}:{key}"

    # ------------------------------------------------------------------ Pub/Sub

    def _start_pubsub_thread(self) -> None:
        """Spawn the daemon Pub/Sub listener that evicts L1 on broadcast."""
        if self._redis is None:
            return
        try:
            thread = threading.Thread(
                target=self._pubsub_loop,
                name="ep-cache-invalidate",
                daemon=True,  # never block process exit
            )
            thread.start()
            self._pubsub_thread = thread
        except Exception as exc:  # noqa: BLE001 — Pub/Sub must never break construction
            logger.warning("cache: failed to start pubsub thread, L1 may go stale: %s", exc)

    def _pubsub_loop(self) -> None:
        """Subscribe to ``ep:cache:invalidate`` and evict L1 on message.

        The message body is a JSON object ``{"cache": str, "key": str}``.
        Reconnects on error (Redis restarts, transient network blips).
        """
        assert self._redis is not None  # noqa: S101 — narrowed for type-checkers
        while not self._pubsub_stop.is_set():
            try:
                pubsub = self._redis.pubsub(ignore_subscribe_messages=True)
                pubsub.subscribe(INVALIDATE_CHANNEL)
                try:
                    for message in pubsub.listen():
                        if self._pubsub_stop.is_set():
                            break
                        if message is None:
                            continue
                        if message.get("type") != "message":
                            continue
                        self._handle_invalidate(message.get("data"))
                finally:
                    try:
                        pubsub.close()
                    except Exception:  # noqa: BLE001 — best-effort cleanup
                        pass
            except Exception as exc:  # noqa: BLE001 — reconnect loop
                if self._pubsub_stop.is_set():
                    break
                logger.warning("cache: pubsub error, reconnecting in 1s: %s", exc)
                # Brief backoff before reconnecting to avoid a tight loop.
                self._pubsub_stop.wait(1.0)

    def _handle_invalidate(self, raw: Any) -> None:
        """Parse an invalidate message and evict the L1 entry."""
        if raw is None:
            return
        try:
            if isinstance(raw, (bytes, bytearray)):
                raw = raw.decode("utf-8")
            payload = json.loads(raw) if isinstance(raw, str) else raw
            cache_name = payload.get("cache")
            key = payload.get("key")
            if not cache_name or not key:
                return
            self._l1_evict(self._full_key(cache_name, key))
        except Exception as exc:  # noqa: BLE001 — a bad message must not kill the thread
            logger.warning("cache: ignoring malformed invalidate message: %s", exc)

    def _publish_invalidate(self, cache_name: str, key: str) -> None:
        """Broadcast an eviction to other processes via the Pub/Sub channel."""
        if self._redis is None:
            return
        try:
            payload = json.dumps({"cache": cache_name, "key": key})
            self._redis.publish(INVALIDATE_CHANNEL, payload)
        except Exception as exc:  # noqa: BLE001 — broadcast failure is non-fatal
            logger.warning("cache: failed to publish invalidate: %s", exc)

    def close(self) -> None:
        """Stop the Pub/Sub thread. Safe to call multiple times."""
        self._pubsub_stop.set()
        # Closing the redis connection (if we own it) unblocks pubsub.listen().
        if self._redis is not None:
            try:
                self._redis.close()
            except Exception:  # noqa: BLE001 — best-effort
                pass
        thread = self._pubsub_thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=2.0)
        self._pubsub_thread = None

    # ------------------------------------------------------------------ public API

    def get_or_load(
        self,
        cache_name: str,
        key: str,
        loader: Callable[[], Any],
        ttl_sec: int = 300,
        cache_empty: bool = False,
    ) -> Any:
        """L1 → L2 → loader lookup, mirroring the Java ``getOrLoad`` semantics.

        - L1 hit (non-expired) → return value immediately.
        - L1 miss, L2 hit → backfill L1, return value.
        - L2 miss → call ``loader``. If the loader returns a non-None value it
          is written to L1+L2. If the loader returns ``None`` and
          ``cache_empty`` is True, the :data:`EMPTY_SENTINEL` is stored for a
          short TTL so repeated empty lookups are cheap; if ``cache_empty`` is
          False the miss is not cached at all.

        The sentinel is transparent: a stored sentinel is returned to the
        caller as ``None`` (the empty result is cached, but never surfaced as
        the literal sentinel string).
        """
        full_key = self._full_key(cache_name, key)

        # ① L1
        hit, value = self._l1_get(full_key)
        if hit:
            return None if value == EMPTY_SENTINEL else value

        # ② L2
        if self._redis is not None:
            try:
                raw = self._redis.get(full_key)
            except Exception as exc:  # noqa: BLE001 — Redis must never break a lookup
                logger.warning("cache.get: redis error, treating as miss: %s", exc)
                raw = None
            if raw is not None:
                value = self._deserialize(raw)
                # Backfill L1 with the remaining TTL if possible, else the
                # configured ttl. Redis returns bytes; decode handled in
                # _deserialize. We don't know the remaining TTL cheaply here,
                # so backfill with the caller's ttl_sec — a reasonable upper
                # bound that prevents L1 from outliving the logical TTL.
                self._l1_put(full_key, value, ttl_sec)
                return None if value == EMPTY_SENTINEL else value

        # ③ loader
        value = loader()
        if value is not None:
            self.put(cache_name, key, value, ttl_sec=ttl_sec)
            return value

        # Empty result handling.
        if cache_empty:
            # Store sentinel with a short TTL (30s) — matches Java SDK which
            # uses 30s for empty sentinels to avoid long-lived negative caches.
            self.put(cache_name, key, EMPTY_SENTINEL, ttl_sec=30)
        return None

    def put(self, cache_name: str, key: str, value: Any, ttl_sec: int = 300) -> None:
        """Write ``value`` to both L1 and L2 (when available)."""
        full_key = self._full_key(cache_name, key)
        self._l1_put(full_key, value, ttl_sec)
        if self._redis is None:
            return
        try:
            self._redis.set(full_key, self._serialize(value), ex=ttl_sec if ttl_sec > 0 else None)
        except Exception as exc:  # noqa: BLE001 — L2 write failure is non-fatal
            logger.warning("cache.put: redis error, skipping L2 store: %s", exc)

    def evict(self, cache_name: str, key: str) -> None:
        """Delete from L1+L2 and broadcast invalidation to other processes."""
        full_key = self._full_key(cache_name, key)
        self._l1_evict(full_key)
        if self._redis is not None:
            try:
                self._redis.delete(full_key)
            except Exception as exc:  # noqa: BLE001 — best-effort
                logger.warning("cache.evict: redis delete error: %s", exc)
        self._publish_invalidate(cache_name, key)

    # ------------------------------------------------------------------ serialization

    @staticmethod
    def _serialize(value: Any) -> str:
        """Serialize a value for L2 storage.

        Strings are stored as-is (so the :data:`EMPTY_SENTINEL` and pre-serialized
        payloads like JSON strings round-trip transparently). Everything else is
        JSON-encoded.
        """
        if isinstance(value, str):
            return value
        return json.dumps(value)

    @staticmethod
    def _deserialize(raw: Any) -> Any:
        """Deserialize a value from L2 storage."""
        if isinstance(raw, (bytes, bytearray)):
            raw = raw.decode("utf-8")
        if not isinstance(raw, str):
            return raw
        # Strings that are NOT valid JSON round-trip as plain strings; valid
        # JSON is parsed back to the original structure. The EMPTY_SENTINEL is
        # a plain string and is returned as-is here (the caller translates it
        # to None).
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            return raw
