"""LangGraph agent graph construction and runner.

Thin wrapper over the hierarchical multi-agent graph in
``ai_agent.graphs.root_graph``. LangGraph is a declared dependency
(``langgraph = "^1.0"`` in pyproject.toml), so no fallback executor is needed.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from .graphs.root_graph import build_root_graph
from .state import AgentState

if TYPE_CHECKING:
    from ep_memory import MemoryManager


def build_graph(memory_manager: "MemoryManager | None" = None):
    """Build and compile the hierarchical multi-agent root graph.

    ``memory_manager`` (an ``ep_memory.MemoryManager``) is wired into the QA
    subgraph's retrieve node so relevant L2/L3 memories are injected into the
    agent state (D-045). When ``None`` the graph runs without memory injection,
    preserving the legacy behavior.
    """
    return build_root_graph(memory_manager=memory_manager)


def run_graph(
    graph,
    messages: list[dict],
    tenant_id: str | None = None,
    user_id: str | None = None,
    session_id: str | None = None,
) -> dict:
    """Invoke the graph with a tenant+user+session scoped thread id.

    The thread id replaces the legacy ``"default"`` constant so checkpoints
    are isolated per conversation.
    """
    initial: AgentState = {
        "messages": messages,
        "current_node": "",
        "current_agent": "",
        "iteration": 0,
        "tenant_id": tenant_id,
        "user_id": user_id,
        "session_id": session_id,
        "context": [],
        "memories": [],
        "reflections": [],
        "trial_count": 0,
        "artifacts": [],
        "final_answer": None,
        "error": None,
    }
    thread_id = f"{tenant_id or 'default'}:{user_id or 'default'}:{session_id or 'default'}"
    return graph.invoke(initial, config={"configurable": {"thread_id": thread_id}})
