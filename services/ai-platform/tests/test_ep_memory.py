"""ep-memory unit tests."""

from __future__ import annotations

import os
import time

import pytest

# Ensure degraded mode: no Redis/Milvus/Mem0 backends.
os.environ.pop("REDIS_URL", None)
# EP_DEV_ONLY=1: allows in-memory fallback for L2/L3/procedural stores
# when Milvus/Mem0/TiDB are unavailable (D-052 Iron Law 0 guard).
os.environ["EP_DEV_ONLY"] = "1"

from ep_memory.config import MemoryConfig
from ep_memory.forgetting import ForgettingPolicy
from ep_memory.l0_immediate import ImmediateMemory
from ep_memory.l1_working import WorkingMemory
from ep_memory.l2_longterm import LongTermMemory
from ep_memory.manager import MemoryManager
from ep_memory.models import Memory, MemoryType


def _msg(role: str, content: str) -> dict:
    return {"role": role, "content": content}


def _config() -> MemoryConfig:
    return MemoryConfig(redis_url=None, mem0_enabled=False)


# --- L0 ---------------------------------------------------------------------


@pytest.mark.asyncio
async def test_l0_immediate_add_get():
    cfg = _config()
    l0 = ImmediateMemory(cfg)
    await l0.add(_msg("user", "hello"), "t1", "s1")
    await l0.add(_msg("assistant", "hi"), "t1", "s1")
    msgs = await l0.get("t1", "s1")
    assert len(msgs) == 2
    assert msgs[0]["content"] == "hello"
    assert msgs[1]["content"] == "hi"


@pytest.mark.asyncio
async def test_l0_truncation():
    cfg = MemoryConfig(redis_url=None, mem0_enabled=False, l0_max_messages=3)
    l0 = ImmediateMemory(cfg)
    for i in range(5):
        overflow = await l0.add(_msg("user", f"m{i}"), "t1", "s1")
        if i < 2:
            assert overflow == [], f"unexpected overflow at i={i}"
    msgs = await l0.get("t1", "s1")
    assert len(msgs) == 3
    # Most recent 3 retained: m2, m3, m4
    assert [m["content"] for m in msgs] == ["m2", "m3", "m4"]
    # Overflow should have evicted m0 and m1 across the last add calls.
    # (Overflow is returned per-add when limit exceeded.)


@pytest.mark.asyncio
async def test_l0_overflow_returns_evicted():
    cfg = MemoryConfig(redis_url=None, mem0_enabled=False, l0_max_messages=2)
    l0 = ImmediateMemory(cfg)
    await l0.add(_msg("user", "a"), "t1", "s1")
    await l0.add(_msg("user", "b"), "t1", "s1")
    overflow = await l0.add(_msg("user", "c"), "t1", "s1")
    assert len(overflow) == 1
    assert overflow[0]["content"] == "a"
    msgs = await l0.get("t1", "s1")
    assert [m["content"] for m in msgs] == ["b", "c"]


# --- L1 ---------------------------------------------------------------------


@pytest.mark.asyncio
async def test_l1_working_summary():
    l1 = WorkingMemory(_config())
    await l1.set_summary("User asked about photosynthesis.", "t1", "u1")
    summary = await l1.get_summary("t1", "u1")
    assert summary == "User asked about photosynthesis."
    ctx = await l1.get_task_context("t1", "u1")
    assert ctx == {}
    await l1.set_task_context({"topic": "biology"}, "t1", "u1")
    ctx = await l1.get_task_context("t1", "u1")
    assert ctx == {"topic": "biology"}


# --- L2 ---------------------------------------------------------------------


@pytest.mark.asyncio
async def test_l2_add_and_search():
    l2 = LongTermMemory(_config())
    msgs = [_msg("user", "The water boils at 100 degrees Celsius.")]
    added = await l2.add(msgs, tenant_id="t1", user_id="u1")
    assert len(added) == 1
    results = await l2.search("water boiling", "t1", "u1", top_k=5)
    assert len(results) >= 1
    assert results[0].content.startswith("[user]")


# --- Manager ----------------------------------------------------------------


@pytest.mark.asyncio
async def test_memory_manager_add():
    mgr = MemoryManager(_config())
    msgs = [
        _msg("user", "What is 2+2?"),
        _msg("assistant", "2+2 equals 4."),
    ]
    await mgr.add(msgs, tenant_id="t1", user_id="u1", session_id="s1")
    immediate = await mgr.l0.get("t1", "s1")
    assert len(immediate) == 2
    # L2 should have extracted memories.
    l2_all = await mgr.l2.list_all("t1", "u1")
    assert len(l2_all) == 2


@pytest.mark.asyncio
async def test_memory_manager_search():
    mgr = MemoryManager(_config())
    msgs = [
        _msg("user", "Photosynthesis converts light into chemical energy."),
        _msg("assistant", "Yes, in chloroplasts."),
    ]
    await mgr.add(msgs, tenant_id="t1", user_id="u1", session_id="s1")
    results = await mgr.search("photosynthesis energy", "t1", "u1")
    assert len(results) >= 1


@pytest.mark.asyncio
async def test_memory_manager_get_context():
    mgr = MemoryManager(_config())
    msgs = [_msg("user", "Hello"), _msg("assistant", "Hi there")]
    await mgr.add(msgs, tenant_id="t1", user_id="u1", session_id="s1")
    ctx = await mgr.get_context("t1", "u1", "s1", query="hello")
    assert len(ctx.immediate_window) == 2
    assert ctx.working_summary == ""
    assert len(ctx.long_term_memories) >= 1
    # get_context without query returns all L2/L3 memories
    ctx2 = await mgr.get_context("t1", "u1", "s1")
    assert len(ctx2.long_term_memories) >= 1


@pytest.mark.asyncio
async def test_memory_manager_overflow_summarizes_to_l1():
    cfg = MemoryConfig(redis_url=None, mem0_enabled=False, l0_max_messages=2)
    mgr = MemoryManager(cfg)
    msgs = [
        _msg("user", "first"),
        _msg("user", "second"),
        _msg("user", "third"),
    ]
    await mgr.add(msgs, tenant_id="t1", user_id="u1", session_id="s1")
    summary = await mgr.l1.get_summary("t1", "u1")
    assert summary != ""
    assert "Summary of" in summary


@pytest.mark.asyncio
async def test_memory_manager_forget():
    mgr = MemoryManager(_config())
    msgs = [_msg("user", "remember this fact")]
    await mgr.add(msgs, tenant_id="t1", user_id="u1", session_id="s1")
    l2_all = await mgr.l2.list_all("t1", "u1")
    assert len(l2_all) == 1
    mem_id = l2_all[0].id
    await mgr.forget(mem_id)
    l2_after = await mgr.l2.list_all("t1", "u1")
    assert all(m.id != mem_id for m in l2_after)


@pytest.mark.asyncio
async def test_memory_manager_promote_to_org():
    mgr = MemoryManager(_config())
    msgs = [_msg("user", "Company policy: remote work allowed on Fridays.")]
    await mgr.add(msgs, tenant_id="t1", user_id="u1", session_id="s1")
    l2_all = await mgr.l2.list_all("t1", "u1")
    mem_id = l2_all[0].id
    promoted = await mgr.promote_to_org(mem_id)
    assert promoted is not None
    assert promoted.metadata.get("promoted_from") == mem_id
    org_list = await mgr.l3.list_all("t1")
    assert len(org_list) == 1
    assert org_list[0].id == promoted.id


# --- Forgetting -------------------------------------------------------------


@pytest.mark.asyncio
async def test_forgetting_policy():
    cfg = _config()
    l2 = LongTermMemory(cfg)
    # Add a memory and artificially age its last_accessed_at.
    msgs = [_msg("user", "old memory that should decay")]
    added = await l2.add(msgs, tenant_id="t1", user_id="u1")
    assert len(added) == 1
    mem = added[0]
    # 40 days ago -> should decay (within 30..90 window).
    mem.last_accessed_at = time.time() - 40 * 86400
    original_score = mem.score

    policy = ForgettingPolicy(cfg, l2)
    result = await policy.run_forgetting_cycle()
    assert result["decayed"] == 1
    assert result["archived"] == 0
    assert mem.score < original_score

    # 100 days ago -> should be archived.
    mem.last_accessed_at = time.time() - 100 * 86400
    result2 = await policy.run_forgetting_cycle()
    assert result2["archived"] == 1
    remaining = await l2.list_all("t1", "u1")
    assert all(m.id != mem.id for m in remaining)
    assert len(policy.archived) == 1


@pytest.mark.asyncio
async def test_l3_admin_only_delete():
    from ep_memory.l3_organizational import OrganizationalMemory

    l3 = OrganizationalMemory(_config())
    mem = Memory(tenant_id="t1", content="org fact")
    await l3.add(mem)
    with pytest.raises(PermissionError):
        await l3.delete(mem.id, is_admin=False)
    ok = await l3.delete(mem.id, is_admin=True)
    assert ok is True


@pytest.mark.asyncio
async def test_l0_clear():
    l0 = ImmediateMemory(_config())
    await l0.add(_msg("user", "x"), "t1", "s1")
    await l0.clear("t1", "s1")
    assert await l0.get("t1", "s1") == []


@pytest.mark.asyncio
async def test_l1_clear():
    l1 = WorkingMemory(_config())
    await l1.set_summary("s", "t1", "u1")
    await l1.clear("t1", "u1")
    assert await l1.get_summary("t1", "u1") == ""


@pytest.mark.asyncio
async def test_memory_manager_run_forgetting():
    mgr = MemoryManager(_config())
    await mgr.add([_msg("user", "old fact")], tenant_id="t1", user_id="u1", session_id="s1")
    result = await mgr.run_forgetting()
    assert "decayed" in result and "archived" in result


@pytest.mark.asyncio
async def test_retriever_hybrid_rrf():
    from ep_memory.retriever import MemoryRetriever, _rrf_fuse

    m1 = Memory(id="a", content="x")
    m2 = Memory(id="b", content="y")
    fused = _rrf_fuse([[m1, m2], [m2, m1]])
    assert {m.id for m in fused} == {"a", "b"}
    # retriever with empty stores returns empty
    mgr = MemoryManager(_config())
    r = MemoryRetriever(mgr.l2, mgr.l3)
    res = await r.retrieve("q", tenant_id="t1", user_id="u1")
    assert isinstance(res, list)
