"""Agent orchestration routes — hierarchical multi-agent graph invocation.

Endpoints:
    POST /v1/agents/run         — Run the root hierarchical graph
    GET  /v1/agents/{agent_id}  — Get agent metadata
    POST /v1/agents/{agent_id}/invoke — Invoke a specific sub-agent directly
"""

from __future__ import annotations

import uuid
from typing import Any

from litestar import Router, get, post
from litestar.exceptions import HTTPException
from pydantic import BaseModel

from ai_agent.config import config as agent_config
from ai_agent.graph import build_graph, run_graph
from ai_agent.industry_config import load_industry_config
from ai_harness.executor import AgentExecutor, ExecutionLimits


class AgentRunRequest(BaseModel):
    """Request to run the root multi-agent graph."""

    messages: list[dict[str, str]]
    tenant_id: str | None = None
    user_id: str | None = None
    session_id: str | None = None
    max_time_sec: float = 120.0


class AgentRunResponse(BaseModel):
    """Response from agent graph execution."""

    request_id: str
    agent: str | None
    answer: str | None
    error: str | None
    elapsed_sec: float
    timed_out: bool = False


# Singleton graph instance (compiled once, reused across requests).
_graph: Any = None
_executor = AgentExecutor(limits=ExecutionLimits(max_time_sec=120))


def _get_graph() -> Any:
    """Lazily build and cache the root graph."""
    global _graph
    if _graph is None:
        _graph = build_graph()
    return _graph


@post("/v1/agents/run", status_code=200)
async def run_agent(data: AgentRunRequest) -> AgentRunResponse:
    """Run the hierarchical multi-agent root graph with Harness guardrails."""
    request_id = str(uuid.uuid4())
    graph = _get_graph()

    # Override executor timeout if requested.
    if data.max_time_sec != _executor.limits.max_time_sec:
        _executor.limits.max_time_sec = data.max_time_sec

    result = await _executor.execute(
        graph,
        initial_state={
            "messages": data.messages,
            "tenant_id": data.tenant_id,
            "user_id": data.user_id,
            "session_id": data.session_id,
            "current_agent": "",
            "current_node": "",
            "iteration": 0,
            "context": [],
            "reflections": [],
            "trial_count": 0,
            "artifacts": [],
            "final_answer": None,
            "error": None,
        },
        config={
            "configurable": {
                "thread_id": f"{data.tenant_id or 'default'}:{data.user_id or 'default'}:{data.session_id or 'default'}",
            }
        },
    )

    output = result.output or {}
    return AgentRunResponse(
        request_id=request_id,
        agent=output.get("current_agent"),
        answer=output.get("final_answer"),
        error=result.error,
        elapsed_sec=result.elapsed_sec,
        timed_out=result.timed_out,
    )


def _build_agents_dict() -> dict:
    """Build the agent registry from the industry config.

    Core agents (``supervisor``, ``qa_agent``) are always present and are not
    industry-specific. Industry agents are populated from
    ``IndustryConfig.agent_graphs``.
    """
    agents = {
        "supervisor": {"name": "supervisor", "type": "router", "description": "Root supervisor agent"},
        "qa_agent": {"name": "qa_agent", "type": "subgraph", "description": "Q&A agent"},
    }
    cfg = load_industry_config()
    for g in cfg.agent_graphs:
        agents[g.name] = {
            "name": g.name,
            "type": "subgraph",
            "description": f"{g.name} agent ({g.module}.{g.builder})",
        }
    return agents


@get("/v1/agents/{agent_id:str}", status_code=200)
async def get_agent(agent_id: str) -> dict:
    """Get agent metadata (stub — returns configured agent info)."""
    agents = _build_agents_dict()
    if agent_id not in agents:
        raise HTTPException(status_code=404, detail=f"Agent '{agent_id}' not found")
    return agents[agent_id]


class AgentInvokeRequest(BaseModel):
    """Direct invocation of a specific sub-agent."""

    messages: list[dict[str, str]]
    tenant_id: str | None = None
    user_id: str | None = None
    session_id: str | None = None


@post("/v1/agents/{agent_id:str}/invoke", status_code=200)
async def invoke_agent(agent_id: str, data: AgentInvokeRequest) -> dict:
    """Invoke a specific sub-agent directly (bypassing supervisor routing)."""
    # Core agents plus all config-registered industry agents are invocable.
    valid = _build_agents_dict().keys()
    if agent_id not in valid:
        raise HTTPException(status_code=404, detail=f"Sub-agent '{agent_id}' not found")

    graph = _get_graph()
    result = await _executor.execute(
        graph,
        initial_state={
            "messages": data.messages,
            "tenant_id": data.tenant_id,
            "user_id": data.user_id,
            "session_id": data.session_id,
            "current_agent": agent_id,  # Force routing to this agent
            "current_node": "",
            "iteration": 0,
            "context": [],
            "reflections": [],
            "trial_count": 0,
            "artifacts": [],
            "final_answer": None,
            "error": None,
        },
    )
    return {
        "agent": agent_id,
        "answer": result.output.get("final_answer") if result.output else None,
        "error": result.error,
        "elapsed_sec": result.elapsed_sec,
    }


agent_router = Router(path="/", route_handlers=[run_agent, get_agent, invoke_agent])
