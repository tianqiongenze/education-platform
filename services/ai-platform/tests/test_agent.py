"""Agent tests."""

from __future__ import annotations

import pytest

from ai_agent.config import config
from ai_agent.graph import build_graph, run_graph
from ai_agent.memory import ConversationMemory


def test_build_graph_compiles():
    g = build_graph()
    assert g is not None


def test_run_graph_simple():
    g = build_graph()
    result = run_graph(
        g,
        messages=[{"role": "user", "content": "What is photosynthesis?"}],
        tenant_id="t1",
        user_id="u1",
        session_id="s1",
    )
    assert result.get("final_answer") is not None
    ans = result["final_answer"]
    assert "photosynthesis" in ans or ans.startswith("Mock reasoning answer") or "Revised" in ans


def test_run_graph_max_iterations(monkeypatch):
    monkeypatch.setattr(config, "max_iterations", 1)
    # Force the think node to hit the iteration cap by pre-setting iteration high
    g = build_graph()
    # Run once: first think succeeds (iteration 0 -> sets final_answer immediately).
    # To trigger the max-iterations error path, feed a state where think has already run max times.
    from ai_agent.nodes import think

    state = {"messages": [{"role": "user", "content": "hi"}], "iteration": 1, "current_node": "think"}
    out = think(state)
    assert out.get("error") == "Max iterations reached"


def test_conversation_memory_add_get():
    mem = ConversationMemory(max_history=5)
    mem.add("s1", "user", "hello")
    mem.add("s1", "assistant", "hi there")
    history = mem.get("s1")
    assert len(history) == 2
    assert history[0]["role"] == "user"
    assert history[1]["content"] == "hi there"


def test_conversation_memory_truncation():
    mem = ConversationMemory(max_history=3)
    for i in range(6):
        mem.add("s1", "user", f"msg{i}")
    history = mem.get("s1")
    assert len(history) == 3
    assert history[0]["content"] == "msg3"
    assert history[-1]["content"] == "msg5"


def test_conversation_memory_clear():
    mem = ConversationMemory()
    mem.add("s1", "user", "hello")
    mem.clear("s1")
    assert mem.get("s1") == []
