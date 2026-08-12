"""GraphRegistry unit tests (SP-04 industry-agnostic refactoring).

Verifies:
- manual registration + build
- dynamic loading from an IndustryConfig
- graceful degradation on import failure
- names() returns the correct set
- thread-safe concurrent build
- empty config produces an empty registry
"""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from ai_agent.graph_registry import GraphRegistry
from ai_agent.industry_config import AgentGraphConfig, IndustryConfig


# ---------------------------------------------------------------------------
# Manual registration + build
# ---------------------------------------------------------------------------


def test_manual_register_and_build():
    """A manually-registered builder is compiled by build_all()."""
    built = {"called": False}

    def _builder():
        built["called"] = True
        return {"compiled": True}

    reg = GraphRegistry()
    reg.register("my_agent", _builder)
    graphs = reg.build_all()

    assert "my_agent" in graphs
    assert graphs["my_agent"] == {"compiled": True}
    assert built["called"] is True
    assert reg.names() == {"my_agent"}


def test_build_all_is_cached():
    """build_all() caches its result; the builder is called only once."""
    calls = {"n": 0}

    def _builder():
        calls["n"] += 1
        return object()

    reg = GraphRegistry()
    reg.register("a", _builder)
    g1 = reg.build_all()
    g2 = reg.build_all()
    assert g1 is g2
    assert calls["n"] == 1


# ---------------------------------------------------------------------------
# Dynamic loading from config
# ---------------------------------------------------------------------------


def test_dynamic_load_from_config_real_module():
    """Loading a config entry pointing at a real module imports and builds it."""
    # Use the qa_agent builder (always available, no external deps).
    cfg = IndustryConfig(
        agent_graphs=[
            AgentGraphConfig(
                name="qa",
                module="ai_agent.graphs.qa_agent",
                builder="build_qa_subgraph",
            )
        ]
    )
    reg = GraphRegistry(cfg)
    reg.load_from_config()
    assert "qa" in reg.names()
    graphs = reg.build_all()
    assert "qa" in graphs
    assert graphs["qa"] is not None


# ---------------------------------------------------------------------------
# Graceful degradation
# ---------------------------------------------------------------------------


def test_graceful_degradation_on_import_failure():
    """A config entry pointing at a nonexistent module is skipped, not raised."""
    cfg = IndustryConfig(
        agent_graphs=[
            AgentGraphConfig(
                name="bad",
                module="nonexistent.pkg.that.does.not.exist",
                builder="build_nothing",
            ),
            AgentGraphConfig(
                name="qa",
                module="ai_agent.graphs.qa_agent",
                builder="build_qa_subgraph",
            ),
        ]
    )
    reg = GraphRegistry(cfg)
    reg.load_from_config()
    # bad module skipped; good module registered
    assert "qa" in reg.names()
    assert "bad" not in reg.names()
    graphs = reg.build_all()
    assert "qa" in graphs
    assert "bad" not in graphs


def test_graceful_degradation_on_missing_builder():
    """A config entry whose module lacks the named builder is skipped."""
    cfg = IndustryConfig(
        agent_graphs=[
            AgentGraphConfig(
                name="x",
                module="ai_agent.graphs.qa_agent",  # real module
                builder="no_such_builder_function",  # but wrong builder name
            )
        ]
    )
    reg = GraphRegistry(cfg)
    reg.load_from_config()
    assert reg.is_empty()
    assert reg.build_all() == {}


def test_graceful_degradation_on_build_failure():
    """A builder that raises is skipped during build_all (not raised)."""
    def _boom():
        raise RuntimeError("boom")

    reg = GraphRegistry()
    reg.register("boom", _boom)
    graphs = reg.build_all()
    assert "boom" not in graphs
    assert graphs == {}


# ---------------------------------------------------------------------------
# names()
# ---------------------------------------------------------------------------


def test_names_returns_registered_set():
    reg = GraphRegistry()
    reg.register("a", lambda: 1)
    reg.register("b", lambda: 2)
    assert reg.names() == {"a", "b"}


def test_names_empty_when_nothing_registered():
    reg = GraphRegistry()
    assert reg.names() == set()
    assert reg.is_empty()


# ---------------------------------------------------------------------------
# Thread-safe concurrent build
# ---------------------------------------------------------------------------


def test_concurrent_build_is_thread_safe():
    """Concurrent build_all() calls produce a consistent result."""
    counter = {"n": 0}

    def _builder():
        counter["n"] += 1
        return f"graph-{counter['n']}"

    reg = GraphRegistry()
    reg.register("agent", _builder)

    results: list[dict] = []
    errors: list[Exception] = []

    def _worker():
        try:
            results.append(reg.build_all())
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _: _worker(), range(20)))

    assert errors == []
    assert len(results) == 20
    # All threads got the same cached dict.
    first = results[0]
    for r in results:
        assert r is first
    # Builder called exactly once due to caching.
    assert counter["n"] == 1


def test_concurrent_register_and_build_no_deadlock():
    """Interleaved register/build calls do not deadlock."""
    reg = GraphRegistry()
    stop = threading.Event()
    errors: list[Exception] = []

    def _builder():
        return object()

    def _registerer():
        i = 0
        while not stop.is_set():
            try:
                reg.register(f"a{i}", _builder)
                i += 1
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)
                break

    def _builder_caller():
        while not stop.is_set():
            try:
                reg.build_all()
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)
                break

    t_reg = threading.Thread(target=_registerer)
    t_build = threading.Thread(target=_builder_caller)
    t_reg.start()
    t_build.start()
    stop.wait(0.5)
    stop.set()
    t_reg.join(timeout=2)
    t_build.join(timeout=2)
    assert errors == []


# ---------------------------------------------------------------------------
# Empty config
# ---------------------------------------------------------------------------


def test_empty_config_produces_empty_registry():
    reg = GraphRegistry(IndustryConfig())
    reg.load_from_config()
    assert reg.is_empty()
    assert reg.names() == set()
    assert reg.build_all() == {}
