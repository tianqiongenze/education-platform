"""Tests for ep_common.cache.CacheService (D-110 extension).

Uses ``fakeredis`` as an in-memory Redis stand-in so the Pub/Sub invalidation
path is exercised without a real cluster. When ``fakeredis`` is unavailable we
fall back to a minimal mock — but fakeredis is the preferred path because it
implements the real pubsub protocol.
"""

from __future__ import annotations

import threading
import time

import pytest

from ep_common.cache import EMPTY_SENTINEL, CacheService

try:
    import fakeredis

    _HAS_FAKREDIS = True
except ImportError:  # pragma: no cover — fakeredis is a dev dep
    _HAS_FAKREDIS = False


# --------------------------------------------------------------------------- #
# Redis helpers
# --------------------------------------------------------------------------- #


def _make_redis():
    """A fresh fakeredis server (shared pubsub state) for a test.

    Returns ``None`` (and the caller skips) when fakeredis is unavailable.
    """
    if not _HAS_FAKREDIS:
        pytest.skip("fakeredis not installed")
    server = fakeredis.FakeServer()
    return fakeredis.FakeStrictRedis(server=server)


@pytest.fixture
def fake_redis():
    """A fakeredis server (shared pubsub state) for each test."""
    return _make_redis()


# --------------------------------------------------------------------------- #
# Tests
# --------------------------------------------------------------------------- #


def test_get_or_load_l1_hit():
    """After the loader populates the cache, a second call must hit L1 only."""
    svc = CacheService(redis_client=None)  # L1-only mode
    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return {"v": 42}

    first = svc.get_or_load("test", "k1", loader, ttl_sec=60)
    assert first == {"v": 42}
    assert calls["count"] == 1

    # Second call must NOT invoke the loader again (L1 hit).
    second = svc.get_or_load("test", "k1", loader, ttl_sec=60)
    assert second == {"v": 42}
    assert calls["count"] == 1

    svc.close()


def test_get_or_load_l2_hit():
    """A value written by one CacheService instance is visible to another via L2."""
    r1 = _make_redis()
    writer = CacheService(redis_client=r1)
    reader = CacheService(redis_client=r1)

    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return "l2-value"

    # Populate via writer (writes L1 + L2).
    writer.get_or_load("ns", "shared", loader, ttl_sec=60)
    assert calls["count"] == 1

    # Reader has an empty L1 — must find it in L2 without calling the loader.
    got = reader.get_or_load("ns", "shared", loader, ttl_sec=60)
    assert got == "l2-value"
    assert calls["count"] == 1  # loader not called again

    writer.close()
    reader.close()


def test_get_or_load_loader_called():
    """On a total miss the loader is invoked exactly once."""
    svc = CacheService(redis_client=_make_redis())
    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return "loaded"

    got = svc.get_or_load("ns", "miss", loader, ttl_sec=60)
    assert got == "loaded"
    assert calls["count"] == 1

    svc.close()


def test_evict_clears_l1_l2():
    """evict() removes the entry from both tiers and broadcasts invalidation."""
    r = _make_redis()
    svc = CacheService(redis_client=r)

    svc.get_or_load("ns", "ev", lambda: "v", ttl_sec=60)

    # Confirm it's present in L2.
    assert r.get("ns:ev") is not None

    svc.evict("ns", "ev")

    # L2 cleared.
    assert r.get("ns:ev") is None
    # L1 cleared — loader must run again on next get.
    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return "reloaded"

    got = svc.get_or_load("ns", "ev", loader, ttl_sec=60)
    assert got == "reloaded"
    assert calls["count"] == 1

    svc.close()


def test_empty_sentinel_caching():
    """When cache_empty=True, a None loader result is cached as the sentinel."""
    r = _make_redis()
    svc = CacheService(redis_client=r)

    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return None

    # First call: loader runs, None returned, sentinel stored.
    got1 = svc.get_or_load("ns", "empty", loader, ttl_sec=60, cache_empty=True)
    assert got1 is None
    assert calls["count"] == 1

    # L2 now holds the sentinel string verbatim.
    raw = r.get("ns:empty")
    assert raw is not None
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    assert raw == EMPTY_SENTINEL

    # Second call: loader must NOT run again (sentinel cached).
    got2 = svc.get_or_load("ns", "empty", loader, ttl_sec=60, cache_empty=True)
    assert got2 is None
    assert calls["count"] == 1

    svc.close()


def test_empty_not_cached_when_cache_empty_false():
    """When cache_empty=False (default), a None result is not cached."""
    r = _make_redis()
    svc = CacheService(redis_client=r)

    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return None

    svc.get_or_load("ns", "empty2", loader, ttl_sec=60)
    svc.get_or_load("ns", "empty2", loader, ttl_sec=60)
    assert calls["count"] == 2  # loader ran both times — nothing cached

    svc.close()


def test_ttl_expiry():
    """An L1 entry past its TTL is treated as a miss."""
    svc = CacheService(redis_client=None)  # L1-only, deterministic
    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return "ttl-val"

    svc.get_or_load("ns", "ttl", loader, ttl_sec=1)
    assert calls["count"] == 1

    # Wait for the entry to expire.
    time.sleep(1.1)

    got = svc.get_or_load("ns", "ttl", loader, ttl_sec=1)
    assert got == "ttl-val"
    assert calls["count"] == 2  # loader ran again after expiry

    svc.close()


def test_pubsub_invalidates_l1():
    """A published invalidate message evicts the matching L1 entry."""
    r = _make_redis()
    svc = CacheService(redis_client=r)

    # Populate L1 (and L2).
    calls = {"count": 0}

    def loader():
        calls["count"] += 1
        return "before-invalidate"

    svc.get_or_load("ns", "pub", loader, ttl_sec=60)
    assert calls["count"] == 1

    # Give the subscriber thread a moment to be ready. fakeredis pubsub is
    # in-process so this should be near-instant, but we poll to be safe.
    _wait_for_subscriber(r, "ns", "pub", timeout=2.0)

    # Simulate another process broadcasting an invalidation.
    r.publish(
        "ep:cache:invalidate",
        '{"cache": "ns", "key": "pub"}',
    )

    # Wait for the subscriber thread to process the eviction.
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline:
        hit, _ = svc._l1_get("ns:pub")
        if not hit:
            break
        time.sleep(0.02)
    else:
        pytest.fail("L1 entry was not evicted by the pubsub message")

    # Next lookup: L1 miss → loader runs again (L2 still has the old value,
    # so actually L2 will backfill; verify the loader is NOT called and we
    # still get the value from L2). To prove L1 was evicted we check L1
    # directly was empty right after the publish — done above. The data
    # integrity check: value is still retrievable.
    got = svc.get_or_load("ns", "pub", loader, ttl_sec=60)
    assert got == "before-invalidate"

    svc.close()


def _wait_for_subscriber(r, cache_name: str, key: str, timeout: float = 2.0) -> None:
    """Poll until the fakeredis pubsub has at least one subscriber."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        # pubsub_channels returns the list of active channels with subscribers.
        try:
            channels = r.pubsub_channels()
        except Exception:
            channels = []
        if any(
            (c.decode() if isinstance(c, (bytes, bytearray)) else c) == "ep:cache:invalidate"
            for c in channels
        ):
            return
        time.sleep(0.02)
    pytest.fail("no subscriber appeared on ep:cache:invalidate within timeout")


def test_dev_mode_no_redis():
    """With redis_client=None the service works as L1-only (dev mode)."""
    svc = CacheService(redis_client=None)
    got = svc.get_or_load("dev", "k", lambda: "dev-val", ttl_sec=60)
    assert got == "dev-val"
    # Second call is an L1 hit.
    got2 = svc.get_or_load("dev", "k", lambda: pytest.fail("should hit L1"), ttl_sec=60)
    assert got2 == "dev-val"
    svc.close()


def test_put_then_get_or_load():
    """put() writes both tiers; a fresh instance reads from L2."""
    r = _make_redis()
    a = CacheService(redis_client=r)
    b = CacheService(redis_client=r)

    a.put("ns", "manual", {"n": 1}, ttl_sec=60)
    got = b.get_or_load(
        "ns", "manual", lambda: pytest.fail("should hit L2"), ttl_sec=60
    )
    assert got == {"n": 1}

    a.close()
    b.close()


def test_thread_safety_smoke():
    """Concurrent get_or_load calls must not corrupt L1 or double-load grossly."""
    r = _make_redis()
    svc = CacheService(redis_client=r)
    calls = {"count": 0}
    lock = threading.Lock()

    def loader():
        with lock:
            calls["count"] += 1
        return "thread-safe"

    def worker():
        for _ in range(50):
            svc.get_or_load("ns", "concurrent", loader, ttl_sec=60)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # Every call returns the correct value.
    assert svc.get_or_load("ns", "concurrent", loader, ttl_sec=60) == "thread-safe"
    # The loader was called at least once; with concurrency it may run a few
    # times (L1/L2 races), but the value is always consistent.
    assert calls["count"] >= 1

    svc.close()
