"""Root hierarchical multi-agent graph.

The supervisor routes to one of the industry-specific subgraphs (loaded from
``IndustryConfig``) or the core ``qa_agent`` subgraph. Each subgraph runs to
completion then returns to the supervisor for another round (or END).

Industry subgraphs are discovered dynamically from the industry config so the
platform code stays industry-agnostic (SP-04 industry-agnostic refactoring).
"""

from __future__ import annotations

import importlib

import structlog

from ..config import config
from ..industry_config import load_industry_config
from ..state import AgentState
from .qa_agent import build_qa_subgraph
from .supervisor import supervisor_node, supervisor_route
from langgraph.graph import END, START, StateGraph

logger = structlog.get_logger(__name__)


def build_checkpointer():
    """Build a Redis-backed checkpointer, falling back to MemorySaver.

    Tries ``langgraph-checkpoint-redis`` first; if unavailable or unconfigured,
    uses the in-memory saver. The fallback is logged via structlog when present.
    """
    # Try Redis checkpointer if a URL is configured and the package is installed.
    redis_url = config.redis_checkpointer_url or config.redis_url
    if redis_url:
        try:
            from langgraph.checkpoint.redis import RedisSaver  # type: ignore

            return RedisSaver.from_conn_string(redis_url)
        except Exception:
            # langgraph-checkpoint-redis not installed; fall through to MemorySaver.
            pass
    # Default: in-memory saver (works without external deps).
    try:
        from langgraph.checkpoint.memory import MemorySaver

        return MemorySaver()
    except Exception:  # pragma: no cover
        return None


def _load_industry_subgraphs() -> dict[str, object]:
    """Dynamically import and build industry subgraphs from config.

    Returns a dict mapping subgraph node name → compiled subgraph. Modules that
    fail to import are logged and skipped (graceful degradation).
    """
    cfg = load_industry_config()
    subgraphs: dict[str, object] = {}
    for entry in cfg.agent_graphs:
        try:
            module = importlib.import_module(entry.module)
        except Exception as exc:  # noqa: BLE001 — graceful degradation
            logger.warning(
                "root_graph.import_failed",
                name=entry.name,
                module=entry.module,
                error=str(exc),
            )
            continue
        builder = getattr(module, entry.builder, None)
        if not callable(builder):
            logger.warning(
                "root_graph.builder_missing",
                name=entry.name,
                module=entry.module,
                builder=entry.builder,
            )
            continue
        try:
            subgraphs[entry.name] = builder()
        except Exception as exc:  # noqa: BLE001 — graceful degradation
            logger.warning(
                "root_graph.build_failed",
                name=entry.name,
                error=str(exc),
            )
    return subgraphs


def build_root_graph(memory_manager=None):
    """Build and compile the hierarchical multi-agent parent graph.

    ``memory_manager`` (an ``ep_memory.MemoryManager`` or None) is forwarded to
    the QA subgraph so its retrieve node can inject L2/L3 memories (D-045).

    Industry subgraphs are loaded dynamically from ``IndustryConfig``; the core
    ``qa_agent`` subgraph is always included. If an industry subgraph fails to
    load it is skipped, and the supervisor's valid-route set is built from the
    successfully registered subgraphs.
    """
    graph = StateGraph(AgentState)
    graph.add_node("supervisor", supervisor_node)

    # Core QA subgraph (always present, not industry-specific).
    graph.add_node("qa_agent", build_qa_subgraph(memory_manager=memory_manager))

    # Industry subgraphs (dynamic).
    industry_subgraphs = _load_industry_subgraphs()
    for name, subgraph in industry_subgraphs.items():
        graph.add_node(name, subgraph)

    # Build the conditional route map from the registered subgraph names.
    route_map: dict[str, str] = {"qa_agent": "qa_agent"}
    for name in industry_subgraphs:
        route_map[name] = name
    route_map["END"] = END

    graph.add_edge(START, "supervisor")
    graph.add_conditional_edges("supervisor", supervisor_route, route_map)

    # Each subgraph returns to the supervisor for the next routing round.
    for name in list(route_map.keys()):
        if name == "END":
            continue
        graph.add_edge(name, "supervisor")

    checkpointer = build_checkpointer()
    if checkpointer is not None:
        return graph.compile(checkpointer=checkpointer)
    return graph.compile()
