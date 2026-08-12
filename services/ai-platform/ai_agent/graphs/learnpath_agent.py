"""Learning-path agent subgraph.

Flow: profile -> graph_reason -> plan -> evaluate -> [reflect -> plan]? -> [review] -> emit
"""

from __future__ import annotations

from ..config import config
from ..reflexion import evaluate_node, reflect_node, should_retry
from ..state import AgentState
from langgraph.graph import END, START, StateGraph


def profile_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "profile", "level": "beginner"}], "current_node": "profile"}


def graph_reason_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "kg", "nodes": 12}], "current_node": "graph_reason"}


def plan_node(state: AgentState) -> dict:
    reflections = state.get("reflections", [])
    if reflections:
        answer = "Revised personalized learning path grounded in knowledge graph and profile."
    else:
        answer = "Mock reasoning answer for: placeholder learning path."
    return {"artifacts": [{"stage": "plan", "steps": []}], "final_answer": answer, "current_node": "plan"}


def review_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "review", "status": "reviewed"}], "current_node": "review"}


def emit_node(state: AgentState) -> dict:
    return {"current_node": "emit"}


def build_learnpath_subgraph():
    graph = StateGraph(AgentState)
    for name, fn in [
        ("profile", profile_node),
        ("graph_reason", graph_reason_node),
        ("plan", plan_node),
        ("evaluate", evaluate_node),
        ("reflect", reflect_node),
        ("review", review_node),
        ("emit", emit_node),
    ]:
        graph.add_node(name, fn)

    graph.add_edge(START, "profile")
    graph.add_edge("profile", "graph_reason")
    graph.add_edge("graph_reason", "plan")
    graph.add_edge("plan", "evaluate")
    graph.add_conditional_edges(
        "evaluate",
        should_retry,
        {"respond": "review", "reflect": "reflect"},
    )
    graph.add_edge("reflect", "plan")
    graph.add_edge("review", "emit")
    graph.add_edge("emit", END)
    return graph.compile(interrupt_before=["review"])
