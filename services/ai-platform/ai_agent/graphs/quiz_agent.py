"""Quiz generation agent subgraph.

Flow: spec -> retrieve -> gen -> dedup -> irt_calibrate -> evaluate
      -> [reflect -> gen]? -> [teacher_audit] -> emit
"""

from __future__ import annotations

from ..config import config
from ..reflexion import evaluate_node, reflect_node, should_retry
from ..state import AgentState
from langgraph.graph import END, START, StateGraph


def spec_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "spec", "type": "mcq", "count": 5}], "current_node": "spec"}


def retrieve_node(state: AgentState) -> dict:
    return {"context": [{"id": "kc-1", "content": "knowledge component"}], "current_node": "retrieve"}


def gen_node(state: AgentState) -> dict:
    reflections = state.get("reflections", [])
    if reflections:
        answer = "Revised MCQ items with distractors aligned to common misconceptions."
    else:
        answer = "Mock reasoning answer for: quiz items placeholder."
    return {"artifacts": [{"stage": "gen", "items": []}], "final_answer": answer, "current_node": "gen"}


def dedup_node(state: AgentState) -> dict:
    return {"current_node": "dedup"}


def irt_calibrate_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "irt", "difficulty": 0.5}], "current_node": "irt_calibrate"}


def teacher_audit_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "audit", "status": "audited"}], "current_node": "teacher_audit"}


def emit_node(state: AgentState) -> dict:
    return {"current_node": "emit"}


def build_quiz_subgraph():
    graph = StateGraph(AgentState)
    for name, fn in [
        ("spec", spec_node),
        ("retrieve", retrieve_node),
        ("gen", gen_node),
        ("dedup", dedup_node),
        ("irt_calibrate", irt_calibrate_node),
        ("evaluate", evaluate_node),
        ("reflect", reflect_node),
        ("teacher_audit", teacher_audit_node),
        ("emit", emit_node),
    ]:
        graph.add_node(name, fn)

    graph.add_edge(START, "spec")
    graph.add_edge("spec", "retrieve")
    graph.add_edge("retrieve", "gen")
    graph.add_edge("gen", "dedup")
    graph.add_edge("dedup", "irt_calibrate")
    graph.add_edge("irt_calibrate", "evaluate")
    graph.add_conditional_edges(
        "evaluate",
        should_retry,
        {"respond": "teacher_audit", "reflect": "reflect"},
    )
    graph.add_edge("reflect", "gen")
    graph.add_edge("teacher_audit", "emit")
    graph.add_edge("emit", END)
    return graph.compile(interrupt_before=["teacher_audit"])
