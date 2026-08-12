"""Grading agent subgraph.

Flow: ingest -> rubric_decompose -> llm_grade -> evaluate -> [reflect -> llm_grade]?
      -> risk_classify -> [teacher_review (HITL)] -> emit

``teacher_review`` is a Human-In-The-Loop checkpoint: the compiled subgraph is
configured with ``interrupt_before=["teacher_review"]`` (ADR-A03).
"""

from __future__ import annotations

from ..config import config
from ..reflexion import evaluate_node, reflect_node, should_retry
from ..state import AgentState
from langgraph.graph import END, START, StateGraph


def ingest_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "ingest", "data": "ingested submission"}], "current_node": "ingest"}


def rubric_decompose_node(state: AgentState) -> dict:
    artifacts = list(state.get("artifacts", []))
    artifacts.append({"stage": "rubric", "criteria": ["correctness", "completeness", "clarity"]})
    return {"artifacts": artifacts, "current_node": "rubric_decompose"}


def llm_grade_node(state: AgentState) -> dict:
    artifacts = list(state.get("artifacts", []))
    reflections = state.get("reflections", [])
    if reflections:
        score = 85
        comment = "Revised grade after reflection: addresses rubric criteria."
    else:
        score = 60
        comment = "Initial grade: requires review."
    artifacts.append({"stage": "grade", "score": score, "comment": comment})
    answer = f"Grade {score}: {comment}"
    return {"artifacts": artifacts, "final_answer": answer, "current_node": "llm_grade"}


def risk_classify_node(state: AgentState) -> dict:
    artifacts = list(state.get("artifacts", []))
    answer = state.get("final_answer") or ""
    # High risk if score < 70 (best-effort heuristic)
    high_risk = "60" in answer  # initial grade is 60 -> high risk
    artifacts.append({"stage": "risk", "high_risk": high_risk})
    return {"artifacts": artifacts, "current_node": "risk_classify"}


def teacher_review_node(state: AgentState) -> dict:
    """HITL node. In production this is a no-op until a human resumes the graph."""
    artifacts = list(state.get("artifacts", []))
    artifacts.append({"stage": "teacher_review", "status": "reviewed"})
    return {"artifacts": artifacts, "current_node": "teacher_review"}


def emit_node(state: AgentState) -> dict:
    artifacts = list(state.get("artifacts", []))
    artifacts.append({"stage": "emit", "status": "emitted"})
    return {"artifacts": artifacts, "current_node": "emit"}


def _risk_route(state: AgentState) -> str:
    artifacts = state.get("artifacts", [])
    high_risk = any(a.get("stage") == "risk" and a.get("high_risk") for a in artifacts)
    return "teacher_review" if high_risk else "emit"


def build_grading_subgraph():
    """Compile and return the grading subgraph."""
    graph = StateGraph(AgentState)
    graph.add_node("ingest", ingest_node)
    graph.add_node("rubric_decompose", rubric_decompose_node)
    graph.add_node("llm_grade", llm_grade_node)
    graph.add_node("evaluate", evaluate_node)
    graph.add_node("reflect", reflect_node)
    graph.add_node("risk_classify", risk_classify_node)
    graph.add_node("teacher_review", teacher_review_node)
    graph.add_node("emit", emit_node)

    graph.add_edge(START, "ingest")
    graph.add_edge("ingest", "rubric_decompose")
    graph.add_edge("rubric_decompose", "llm_grade")
    graph.add_edge("llm_grade", "evaluate")
    graph.add_conditional_edges(
        "evaluate",
        should_retry,
        {"respond": "risk_classify", "reflect": "reflect"},
    )
    graph.add_edge("reflect", "llm_grade")
    graph.add_conditional_edges(
        "risk_classify",
        _risk_route,
        {"teacher_review": "teacher_review", "emit": "emit"},
    )
    graph.add_edge("teacher_review", "emit")
    graph.add_edge("emit", END)

    # HITL: pause before teacher_review so a human can intervene (ADR-A03).
    # recursion_limit caps the reflect->llm_grade retry loop.
    return graph.compile(interrupt_before=["teacher_review"]).with_config(
        {"recursion_limit": config.reflexion_max_trials * 4 + 10}
    )
