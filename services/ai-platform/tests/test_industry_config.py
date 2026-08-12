"""IndustryConfig unit tests (SP-04 industry-agnostic refactoring).

These tests verify the YAML-driven industry configuration:
- loading from education.yaml populates all fields
- missing file / EP_DEV_ONLY / invalid YAML fall back to minimal generic config
- sub-sections (tool_catalog, intent_keywords, event_routes, agent_graphs) parse

Each test manages the module-level cache and environment variables itself to
avoid cross-test contamination (the cache is process-global).
"""

from __future__ import annotations

import os
import textwrap
from pathlib import Path

import pytest

# Path to the real education.yaml shipped with the service.
_SVC_ROOT = Path(__file__).resolve().parent.parent
_EDU_YAML = _SVC_ROOT / "config" / "education.yaml"


@pytest.fixture(autouse=True)
def _reset_config_cache(monkeypatch):
    """Reset the industry config cache and env before each test."""
    from ai_agent import industry_config

    industry_config.reset_cache()
    monkeypatch.delenv("INDUSTRY_CONFIG_PATH", raising=False)
    yield
    industry_config.reset_cache()


# ---------------------------------------------------------------------------
# YAML loading from education.yaml
# ---------------------------------------------------------------------------


def test_load_education_yaml_all_fields_populated():
    from ai_agent.industry_config import load_industry_config

    cfg = load_industry_config(str(_EDU_YAML))
    assert cfg.domain_name == "education"
    assert cfg.system_prompt == "You are EP education assistant."
    # agent_graphs
    assert len(cfg.agent_graphs) == 5
    names = {g.name for g in cfg.agent_graphs}
    assert names == {"grading_agent", "quiz_agent", "learnpath_agent", "digital_human", "debate"}
    # spot-check one agent_graph entry
    grading = next(g for g in cfg.agent_graphs if g.name == "grading_agent")
    assert grading.module == "ai_agent.graphs.grading_agent"
    assert grading.builder == "build_grading_subgraph"
    # tool_catalog
    assert len(cfg.tool_catalog) == 6
    tool_names = {t.name for t in cfg.tool_catalog}
    assert "grade_submission" in tool_names
    assert "create_learnpath" in tool_names
    # intent_keywords
    assert "grading_agent" in cfg.intent_keywords
    assert "批改" in cfg.intent_keywords["grading_agent"]
    # event_routes
    assert cfg.event_routes["SUBMISSION_CREATED"] == ["grading-agent"]
    assert "learnpath-agent" in cfg.event_routes["GRADING_COMPLETED"]


# ---------------------------------------------------------------------------
# Missing file -> minimal default
# ---------------------------------------------------------------------------


def test_load_missing_file_returns_minimal_default():
    from ai_agent.industry_config import load_industry_config

    cfg = load_industry_config("/nonexistent/path/no-such-file.yaml")
    assert cfg.domain_name == "generic"
    assert cfg.system_prompt == "You are an AI assistant."
    assert cfg.agent_graphs == []
    assert cfg.tool_catalog == []
    assert cfg.intent_keywords == {}
    assert cfg.event_routes == {}


# ---------------------------------------------------------------------------
# EP_DEV_ONLY active -> minimal default (industry config does not depend on
# external services, but the guard is still honored for missing-file fallback)
# ---------------------------------------------------------------------------


def test_dev_only_with_missing_file_returns_minimal(monkeypatch):
    from ai_agent import industry_config
    from ai_agent.industry_config import load_industry_config

    monkeypatch.setenv("EP_DEV_ONLY", "1")
    industry_config.reset_cache()
    cfg = load_industry_config("/nonexistent/path/no-such-file.yaml")
    assert cfg.domain_name == "generic"
    assert cfg.system_prompt == "You are an AI assistant."


# ---------------------------------------------------------------------------
# Invalid YAML -> graceful fallback
# ---------------------------------------------------------------------------


def test_invalid_yaml_returns_minimal_default(tmp_path):
    from ai_agent.industry_config import load_industry_config

    bad = tmp_path / "bad.yaml"
    bad.write_text("domain_name: [unterminated\n  - this is : : invalid", encoding="utf-8")
    cfg = load_industry_config(str(bad))
    assert cfg.domain_name == "generic"
    assert cfg.system_prompt == "You are an AI assistant."


def test_invalid_yaml_structure_returns_minimal(tmp_path):
    from ai_agent.industry_config import load_industry_config

    bad = tmp_path / "list.yaml"
    bad.write_text("- this\n- is\n- a list\n", encoding="utf-8")
    cfg = load_industry_config(str(bad))
    assert cfg.domain_name == "generic"


# ---------------------------------------------------------------------------
# Sub-section parsing
# ---------------------------------------------------------------------------


def test_tool_catalog_parsing(tmp_path):
    from ai_agent.industry_config import load_industry_config

    y = tmp_path / "c.yaml"
    y.write_text(
        textwrap.dedent(
            """
            domain_name: test
            tool_catalog:
              - name: tool_a
                server: srv_a
                description: does a
              - name: tool_b
                server: srv_b
                description: does b
            """
        ),
        encoding="utf-8",
    )
    cfg = load_industry_config(str(y))
    assert len(cfg.tool_catalog) == 2
    assert cfg.tool_catalog[0].name == "tool_a"
    assert cfg.tool_catalog[0].server == "srv_a"
    assert cfg.tool_catalog[1].description == "does b"


def test_intent_keywords_parsing(tmp_path):
    from ai_agent.industry_config import load_industry_config

    y = tmp_path / "k.yaml"
    y.write_text(
        textwrap.dedent(
            """
            domain_name: test
            intent_keywords:
              agent_one: [alpha, beta]
              agent_two: [gamma, delta]
            """
        ),
        encoding="utf-8",
    )
    cfg = load_industry_config(str(y))
    assert cfg.intent_keywords["agent_one"] == ["alpha", "beta"]
    assert cfg.intent_keywords["agent_two"] == ["gamma", "delta"]


def test_event_routes_parsing(tmp_path):
    from ai_agent.industry_config import load_industry_config

    y = tmp_path / "r.yaml"
    y.write_text(
        textwrap.dedent(
            """
            domain_name: test
            event_routes:
              EVENT_A: [agent-a, agent-b]
              EVENT_B: [agent-c]
            """
        ),
        encoding="utf-8",
    )
    cfg = load_industry_config(str(y))
    assert cfg.event_routes["EVENT_A"] == ["agent-a", "agent-b"]
    assert cfg.event_routes["EVENT_B"] == ["agent-c"]


def test_agent_graphs_parsing(tmp_path):
    from ai_agent.industry_config import load_industry_config

    y = tmp_path / "g.yaml"
    y.write_text(
        textwrap.dedent(
            """
            domain_name: test
            agent_graphs:
              - name: my_agent
                module: pkg.mod
                builder: build_it
              - name: other
                module: pkg.other
                builder: build_other
            """
        ),
        encoding="utf-8",
    )
    cfg = load_industry_config(str(y))
    assert len(cfg.agent_graphs) == 2
    assert cfg.agent_graphs[0].name == "my_agent"
    assert cfg.agent_graphs[0].module == "pkg.mod"
    assert cfg.agent_graphs[0].builder == "build_it"


# ---------------------------------------------------------------------------
# Caching
# ---------------------------------------------------------------------------


def test_config_is_cached_per_process():
    from ai_agent.industry_config import load_industry_config

    cfg1 = load_industry_config(str(_EDU_YAML))
    cfg2 = load_industry_config(str(_EDU_YAML))
    assert cfg1 is cfg2


# ---------------------------------------------------------------------------
# to_dict round-trip
# ---------------------------------------------------------------------------


def test_industry_config_to_dict_roundtrip():
    from ai_agent.industry_config import AgentGraphConfig, IndustryConfig, ToolConfig

    cfg = IndustryConfig(
        domain_name="demo",
        system_prompt="hi",
        agent_graphs=[AgentGraphConfig(name="a", module="m", builder="b")],
        tool_catalog=[ToolConfig(name="t", server="s", description="d")],
        intent_keywords={"a": ["x"]},
        event_routes={"E": ["a"]},
    )
    d = cfg.to_dict()
    assert d["domain_name"] == "demo"
    rebuilt = IndustryConfig.from_dict(d)
    assert rebuilt.domain_name == "demo"
    assert rebuilt.agent_graphs[0].name == "a"
    assert rebuilt.tool_catalog[0].name == "t"
    assert rebuilt.intent_keywords == {"a": ["x"]}
    assert rebuilt.event_routes == {"E": ["a"]}
