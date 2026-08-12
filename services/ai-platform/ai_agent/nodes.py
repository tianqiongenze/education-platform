"""Agent node functions, factories, and conditional edges.

Provides:
- Backward-compatible module-level nodes: retrieve_context, think, respond, should_continue
- Factory functions for subgraph composition: make_retrieve_node, make_think_node,
  make_respond_node — these accept optional config and return parameterized nodes.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Callable

from .config import config
from .state import AgentState

if TYPE_CHECKING:
    from ep_memory import MemoryManager


def _last_user_message(state: AgentState) -> str:
    for m in reversed(state.get("messages", [])):
        if m.get("role") == "user":
            return m.get("content", "")
    return ""


# --- Module-level nodes (backward compatible) --------------------------------


def retrieve_context(state: AgentState) -> dict:
    """Retrieve mock context. Kept for backward compatibility."""
    return make_retrieve_node()(state)


def think(state: AgentState) -> dict:
    """Generate a mock answer. Kept for backward compatibility."""
    return make_think_node()(state)


def respond(state: AgentState) -> dict:
    """Pass through final_answer. Kept for backward compatibility."""
    return make_respond_node()(state)


def should_continue(state: AgentState) -> str:
    """Conditional edge for the legacy single-agent graph."""
    if state.get("error"):
        return "end"
    if state.get("final_answer") and state.get("evaluation_passed", True):
        return "end"
    if state.get("iteration", 0) < config.max_iterations:
        return "think"
    return "end"


# --- Factory functions -------------------------------------------------------


def make_retrieve_node(
    rag_config: dict | None = None,
    memory_manager: "MemoryManager | None" = None,
) -> Callable[[AgentState], dict]:
    """Return a retrieve node.

    ``rag_config`` is reserved for wiring a real RAG retriever (collection
    name, top_k, filters, etc.) in a future iteration.

    When ``memory_manager`` (an ``ep_memory.MemoryManager``) is supplied and
    ``config.ep_memory_enabled`` is True, the node performs a best-effort L2/L3
    memory search against the last user message and injects the results into
    the ``memories`` field of the agent state (D-045). Memory retrieval errors
    are swallowed so the graph never breaks due to memory-system failures.
    """

    def _retrieve(state: AgentState) -> dict:
        last_user = _last_user_message(state)
        if last_user:
            ctx = [
                {
                    "id": "doc-1",
                    "content": f"Mock retrieved context for: {last_user[:40]}",
                    "score": 0.95,
                }
            ]
        else:
            ctx = []

        memories: list[dict] = []
        if memory_manager is not None and getattr(config, "ep_memory_enabled", True):
            tenant_id = state.get("tenant_id") or "default"
            user_id = state.get("user_id") or "default"
            try:
                mgr = memory_manager
                import asyncio

                async def _mem_search() -> list[dict]:
                    top_k = getattr(config, "ep_memory_top_k", 5)
                    found = await mgr.search(last_user, tenant_id, user_id, top_k=top_k)
                    return [m.to_dict() for m in found]

                try:
                    loop = asyncio.get_event_loop()
                    if loop.is_running():
                        import threading

                        holder: dict[str, list[dict]] = {}

                        def _worker() -> None:
                            new_loop = asyncio.new_event_loop()
                            try:
                                holder["value"] = new_loop.run_until_complete(_mem_search())
                            finally:
                                new_loop.close()

                        t = threading.Thread(target=_worker)
                        t.start()
                        t.join()
                        memories = holder.get("value", [])
                    else:
                        memories = loop.run_until_complete(_mem_search())
                except RuntimeError:
                    memories = asyncio.run(_mem_search())
            except Exception:
                memories = []

        update: dict = {"context": ctx, "current_node": "retrieve"}
        if memories:
            update["memories"] = memories
        return update

    return _retrieve


def make_think_node(llm_config: dict | None = None) -> Callable[[AgentState], dict]:
    """Return a think node.

    Today produces a deterministic mock answer that incorporates prior
    reflections (so the Reflexion loop can converge on retries). ``llm_config``
    is reserved for wiring a real LLM (model name, temperature, tools, etc.).
    """

    def _think(state: AgentState) -> dict:
        iteration = state.get("iteration", 0)
        if iteration >= config.max_iterations:
            return {"error": "Max iterations reached", "current_node": "think"}
        last_user = _last_user_message(state)
        reflections = state.get("reflections", [])
        # When reflections exist, produce a grounded, specific answer that will
        # pass the evaluator. Otherwise emit the legacy mock (which the rule
        # evaluator flags as a placeholder) to exercise the Reflexion loop.
        if reflections:
            answer = (
                f"Based on retrieved context, photosynthesis and related concepts "
                f"explain: {last_user[:50]}. Revised answer addressing prior feedback."
            )
        else:
            answer = f"Mock reasoning answer for: {last_user[:50]}"
        return {
            "current_node": "think",
            "iteration": iteration + 1,
            "final_answer": answer,
        }

    return _think


def make_respond_node() -> Callable[[AgentState], dict]:
    """Return a respond node that passes through final_answer."""

    def _respond(state: AgentState) -> dict:
        answer = state.get("final_answer") or "No answer generated."
        return {"current_node": "respond", "final_answer": answer}

    return _respond
