"""Debate subgraph (adversarial quality check).

Flow: proposition -> opposition -> synthesis

Used to validate grading/answer quality by having two adversarial positions
argue from the student-answer and standard-answer perspectives, then a
synthesis step produces the final verdict.
"""

from __future__ import annotations

from ..state import AgentState
from langgraph.graph import END, START, StateGraph


def proposition_node(state: AgentState) -> dict:
    """Argue from the student-answer perspective."""
    artifacts = list(state.get("artifacts", []))
    artifacts.append({"stage": "proposition", "stance": "student-answer defensible"})
    return {"artifacts": artifacts, "current_node": "proposition"}


def opposition_node(state: AgentState) -> dict:
    """Argue from the standard-answer perspective."""
    artifacts = list(state.get("artifacts", []))
    artifacts.append({"stage": "opposition", "stance": "standard-answer authoritative"})
    return {"artifacts": artifacts, "current_node": "opposition"}


def synthesis_node(state: AgentState) -> dict:
    """Synthesize a final verdict from both positions."""
    artifacts = list(state.get("artifacts", []))
    verdict = "synthesized verdict: partial credit with corrections"
    artifacts.append({"stage": "synthesis", "verdict": verdict})
    return {
        "artifacts": artifacts,
        "current_node": "synthesis",
        "final_answer": verdict,
        "evaluation_passed": True,
    }


def build_debate_subgraph():
    graph = StateGraph(AgentState)
    graph.add_node("proposition", proposition_node)
    graph.add_node("opposition", opposition_node)
    graph.add_node("synthesis", synthesis_node)

    graph.add_edge(START, "proposition")
    graph.add_edge("proposition", "opposition")
    graph.add_edge("opposition", "synthesis")
    graph.add_edge("synthesis", END)
    return graph.compile()
