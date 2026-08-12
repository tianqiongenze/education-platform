"""QA (question-answering) agent subgraph.

Flow: retrieve -> think -> evaluate -> [reflect -> think]? -> respond
retrieve/think are mock implementations today; future versions will wire to a
real RAG retriever and an LLM respectively.
"""

from __future__ import annotations

from ..config import config
from ..nodes import make_respond_node, make_retrieve_node, make_think_node
from ..reflexion import evaluate_node, reflect_node, should_retry
from ..state import AgentState
from langgraph.graph import END, START, StateGraph


def build_qa_subgraph(memory_manager=None):
    """Compile and return the QA subgraph.

    ``memory_manager`` (an ``ep_memory.MemoryManager`` or None) is wired into
    the retrieve node so L2/L3 memories are injected into the state (D-045).
    """
    retrieve = make_retrieve_node(memory_manager=memory_manager)
    think = make_think_node()
    respond = make_respond_node()

    graph = StateGraph(AgentState)
    graph.add_node("retrieve", retrieve)
    graph.add_node("think", think)
    graph.add_node("evaluate", evaluate_node)
    graph.add_node("reflect", reflect_node)
    graph.add_node("respond", respond)

    graph.add_edge(START, "retrieve")
    graph.add_edge("retrieve", "think")
    graph.add_edge("think", "evaluate")
    # evaluate -> respond | reflect
    graph.add_conditional_edges(
        "evaluate",
        should_retry,
        {"respond": "respond", "reflect": "reflect"},
    )
    # reflect loops back to think for another attempt
    graph.add_edge("reflect", "think")
    graph.add_edge("respond", END)
    # recursion_limit enforces depth control for the reflect->think retry loop.
    return graph.compile().with_config({"recursion_limit": config.reflexion_max_trials * 4 + 6})
