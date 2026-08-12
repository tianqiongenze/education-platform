"""Hierarchical multi-agent graph tests (D-046 WP3)."""

from __future__ import annotations

import pytest

from ai_agent.config import config
from ai_agent.graphs.debate import build_debate_subgraph
from ai_agent.graphs.grading_agent import build_grading_subgraph
from ai_agent.graphs.qa_agent import build_qa_subgraph
from ai_agent.graphs.root_graph import build_root_graph
from ai_agent.graphs.supervisor import supervisor_node, supervisor_route
from ai_agent.reflexion import evaluate_node, reflect_node, should_retry


def test_build_root_graph_compiles():
    g = build_root_graph()
    assert g is not None
    assert hasattr(g, "invoke")


def test_supervisor_routes_to_qa():
    state = {"messages": [{"role": "user", "content": "What is photosynthesis?"}]}
    out = supervisor_node(state)
    assert out["current_agent"] == "qa_agent"
    assert supervisor_route(out) == "qa_agent"


def test_supervisor_routes_to_grading():
    state = {"messages": [{"role": "user", "content": "Please grade this essay"}]}
    out = supervisor_node(state)
    assert out["current_agent"] == "grading_agent"
    assert supervisor_route(out) == "grading_agent"


def test_supervisor_routes_to_quiz():
    state = {"messages": [{"role": "user", "content": "generate a quiz on algebra"}]}
    out = supervisor_node(state)
    assert out["current_agent"] == "quiz_agent"


def test_reflexion_retry_on_fail():
    """evaluate failure with trial_count < max -> route to reflect."""
    state = {
        "final_answer": "Mock reasoning answer for: x",  # contains placeholder marker
        "trial_count": 0,
        "messages": [{"role": "user", "content": "explain gravity"}],
    }
    out = evaluate_node(state)
    assert out["evaluation_passed"] is False
    assert out["trial_count"] == 1
    assert should_retry(out) == "reflect"


def test_reflexion_pass():
    """A grounded, non-placeholder answer passes evaluation."""
    state = {
        "final_answer": "Photosynthesis converts light energy into chemical energy stored in glucose.",
        "trial_count": 0,
        "messages": [{"role": "user", "content": "explain photosynthesis"}],
    }
    out = evaluate_node(state)
    assert out["evaluation_passed"] is True
    assert should_retry(out) == "respond"


def test_reflexion_max_trials():
    """Depth control is delegated to LangGraph's recursion_limit; should_retry
    always routes to 'reflect' on failure (the graph terminates when the
    recursion limit is hit)."""
    state = {
        "final_answer": "Mock reasoning answer for: x",
        "trial_count": config.reflexion_max_trials,
        "messages": [{"role": "user", "content": "explain gravity"}],
    }
    out = evaluate_node(state)
    # should_retry no longer gates on trial_count — LangGraph recursion_limit
    # enforces depth. On failure it always routes to "reflect".
    assert should_retry(out) == "reflect"


def test_reflect_node_appends_reflection():
    state = {
        "final_answer": "bad answer",
        "feedback": "too short",
        "trial_count": 1,
        "reflections": ["trial 0: was empty"],
    }
    out = reflect_node(state)
    assert len(out["reflections"]) == 2
    assert out["reflections"][-1].startswith("Trial 1")


def test_qa_subgraph_reflexion_converges():
    """QA subgraph should retry then converge on a grounded answer."""
    g = build_qa_subgraph()
    state = {
        "messages": [{"role": "user", "content": "What is photosynthesis?"}],
        "iteration": 0,
        "trial_count": 0,
        "reflections": [],
        "context": [],
        "final_answer": None,
        "error": None,
    }
    result = g.invoke(
        state,
        config={"configurable": {"thread_id": "test-qa-1"}},
    )
    ans = result.get("final_answer") or ""
    # After reflexion the answer should be the revised grounded form.
    assert "Revised" in ans or "photosynthesis" in ans


def test_debate_subgraph():
    g = build_debate_subgraph()
    state = {"messages": [], "artifacts": []}
    result = g.invoke(
        state,
        config={"configurable": {"thread_id": "test-debate-1"}},
    )
    stages = [a.get("stage") for a in result.get("artifacts", [])]
    assert "proposition" in stages
    assert "opposition" in stages
    assert "synthesis" in stages
    assert result.get("final_answer") is not None


def test_grading_hitl_interrupt():
    """Grading subgraph should interrupt before teacher_review for high-risk cases."""
    g = build_grading_subgraph()
    state = {
        "messages": [{"role": "user", "content": "grade this essay"}],
        "iteration": 0,
        "trial_count": 0,
        "reflections": [],
        "artifacts": [],
        "final_answer": None,
        "error": None,
    }
    result = g.invoke(
        state,
        config={"configurable": {"thread_id": "test-grading-1"}},
    )
    # Because interrupt_before=["teacher_review"], execution stops before that node.
    stages = [a.get("stage") for a in result.get("artifacts", [])]
    assert "ingest" in stages
    assert "rubric" in stages
    # teacher_review should NOT have run yet (interrupted)
    assert "teacher_review" not in stages
