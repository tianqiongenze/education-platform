"""D-119: Testcontainers-backed integration test for ep_memory.

Starts an isolated Redis container (no shared cluster) and exercises the
L0-L3 memory system end-to-end:

  * L0 ImmediateMemory  — RPUSH/LRANGE on `mem:imm:{tenant}:{session}`
  * L1 WorkingMemory    — SET/GET on `mem:work:{tenant}:{user}`
  * MemoryManager.add() — overflow → summarize → L1 summary aggregation
  * MemoryManager.get_context() — aggregates L0 + L1 into a ContextWindow

Constraints honored (verbatim from D-119):
  * "Do NOT connect to shared cluster infrastructure (TiDB/Redis/Pulsar) —
     use Testcontainers isolated containers"
  * "Testcontainers tests must be self-contained (start and stop their own
     containers)"

Skips cleanly when Docker is unavailable so the core suite is not blocked.
"""

from __future__ import annotations

import asyncio
import os
import uuid

import pytest

pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.integration,
]


def _docker_available() -> bool:
    """Best-effort Docker detection. The testcontainers library itself will
    raise a more specific error if Docker is not running, but we probe first
    to give a cleaner skip message."""
    import shutil
    import subprocess

    docker = shutil.which("docker")
    if not docker:
        return False
    try:
        subprocess.run(
            [docker, "info"],
            capture_output=True,
            timeout=10,
            check=False,
        ).check_returncode()
        return True
    except Exception:
        return False


@pytest.fixture(scope="module")
def redis_container():
    """Start an isolated Redis container for the duration of the module.

    Self-contained: started here, stopped here. No shared cluster connection.
    """
    if not _docker_available():
        pytest.skip("Docker not available — skipping Testcontainers Redis IT")

    try:
        from testcontainers.redis import RedisContainer
    except ImportError as exc:  # pragma: no cover - dependency guard
        pytest.skip(f"testcontainers not installed: {exc}")

    container = RedisContainer()
    try:
        container.start()
    except Exception as exc:  # pragma: no cover - env-specific
        pytest.skip(f"Could not start Redis container: {exc}")

    # testcontainers-redis exposes the connection string via get_container_host_ip
    # + exposed ports. Build a redis:// URL.
    host = container.get_container_host_ip()
    port = int(container.get_exposed_port(6379))
    redis_url = f"redis://{host}:{port}/0"

    yield redis_url

    try:
        container.stop()
    except Exception:
        pass


@pytest.fixture()
def memory_config(redis_container):
    """Build a MemoryConfig pointed at the testcontainers Redis URL.

    mem0 is disabled so MemoryManager.add() does not attempt to reach a
    Milvus/SP04-gateway backend — L2 extraction runs against the in-memory
    fallback, which is sufficient for the L0/L1 real-Redis assertions here.
    """
    from ep_memory.config import MemoryConfig

    cfg = MemoryConfig(redis_url=redis_container, mem0_enabled=False)
    return cfg


@pytest.fixture()
def manager(memory_config):
    from ep_memory.manager import MemoryManager

    return MemoryManager(memory_config)


@pytest.fixture()
def ids():
    """Random tenant/user/session ids per test for isolation."""
    suffix = uuid.uuid4().hex[:8]
    return {
        "tenant_id": f"tenant-{suffix}",
        "user_id": f"user-{suffix}",
        "session_id": f"session-{suffix}",
    }


def test_l0_immediate_add_then_get_real_redis(redis_container, ids):
    """L0 ImmediateMemory writes to and reads from the real Redis container."""
    from ep_memory.config import MemoryConfig
    from ep_memory.l0_immediate import ImmediateMemory

    cfg = MemoryConfig(redis_url=redis_container, l0_max_messages=10)
    l0 = ImmediateMemory(cfg)

    tenant, session = ids["tenant_id"], ids["session_id"]
    msg = {"role": "user", "content": "hello testcontainers"}

    asyncio.run(l0.add(msg, tenant, session))
    result = asyncio.run(l0.get(tenant, session))

    assert len(result) == 1
    assert result[0]["content"] == "hello testcontainers"
    assert result[0].get("ts") is not None


def test_l0_immediate_overflow_triggers_eviction(redis_container, ids):
    """When L0 exceeds l0_max_messages, the oldest entries overflow."""
    from ep_memory.config import MemoryConfig
    from ep_memory.l0_immediate import ImmediateMemory

    cfg = MemoryConfig(redis_url=redis_container, l0_max_messages=3)
    l0 = ImmediateMemory(cfg)

    tenant, session = ids["tenant_id"], ids["session_id"]

    async def _run():
        overflows = []
        for i in range(5):
            ov = await l0.add({"role": "user", "content": f"msg-{i}"}, tenant, session)
            overflows.extend(ov)
        remaining = await l0.get(tenant, session)
        return overflows, remaining

    overflows, remaining = asyncio.run(_run())

    assert len(remaining) == 3
    assert remaining[0]["content"] == "msg-2"
    assert remaining[-1]["content"] == "msg-4"
    # 2 messages overflowed (msg-0, msg-1)
    assert len(overflows) == 2
    assert overflows[0]["content"] == "msg-0"


def test_l1_working_summary_round_trip_real_redis(redis_container, ids):
    """L1 WorkingMemory SET/GET summary round-trips through real Redis."""
    from ep_memory.config import MemoryConfig
    from ep_memory.l1_working import WorkingMemory

    cfg = MemoryConfig(redis_url=redis_container)
    l1 = WorkingMemory(cfg)

    tenant, user = ids["tenant_id"], ids["user_id"]

    async def _run():
        await l1.set_summary("session summary A", tenant, user)
        return await l1.get_summary(tenant, user)

    assert asyncio.run(_run()) == "session summary A"


def test_manager_add_propagates_to_l0_and_l1(manager, ids):
    """MemoryManager.add() routes messages to L0 and summarizes overflow into L1."""
    tenant, user, session = ids["tenant_id"], ids["user_id"], ids["session_id"]

    # config has l0_max_messages=10 default; send 12 to force one overflow.
    messages = [{"role": "user", "content": f"turn-{i}"} for i in range(12)]

    asyncio.run(manager.add(messages, tenant, user, session))

    immediate = asyncio.run(manager.l0.get(tenant, session))
    assert len(immediate) == 10
    assert immediate[0]["content"] == "turn-2"
    assert immediate[-1]["content"] == "turn-11"

    summary = asyncio.run(manager.l1.get_summary(tenant, user))
    # summarize() produces "Summary of N messages (M user turns)."
    assert summary.startswith("Summary of")
    assert "user turns" in summary


def test_manager_get_context_aggregates_l0_and_l1(manager, ids):
    """get_context() returns a ContextWindow with L0 messages + L1 summary."""
    tenant, user, session = ids["tenant_id"], ids["user_id"], ids["session_id"]

    asyncio.run(
        manager.add(
            [{"role": "user", "content": "context-propagation-check"}],
            tenant,
            user,
            session,
        )
    )

    ctx = asyncio.run(manager.get_context(tenant, user, session))

    assert any(m["content"] == "context-propagation-check" for m in ctx.immediate_window)
    # working_summary is "" when no overflow occurred — acceptable here.
    assert isinstance(ctx.working_summary, str)


def test_l0_persistence_is_real_redis_not_fallback(redis_container, ids):
    """A NEW ImmediateMemory instance pointed at the same Redis URL reads
    previously-written data — proving the bytes landed in the container, not
    the in-memory fallback dict (which would be per-instance)."""
    from ep_memory.config import MemoryConfig
    from ep_memory.l0_immediate import ImmediateMemory

    cfg = MemoryConfig(redis_url=redis_container)
    tenant, session = ids["tenant_id"], ids["session_id"]

    writer = ImmediateMemory(cfg)
    asyncio.run(writer.add({"role": "assistant", "content": "persisted-via-redis"}, tenant, session))

    reader = ImmediateMemory(cfg)
    result = asyncio.run(reader.get(tenant, session))

    assert len(result) == 1
    assert result[0]["content"] == "persisted-via-redis"
