"""A2A protocol routes — Agent-to-Agent task lifecycle endpoints.

Exposes the A2A JSON-RPC server and task management endpoints via Litestar.
Mounts the A2A router created by ``ai_a2a.server.create_a2a_router``.
"""

from __future__ import annotations

from litestar import Router, get, post

from ai_a2a.server import create_a2a_router
from ai_a2a.agent_card import AgentCard, Skill
from ai_a2a.registry import AgentRegistry
from ai_a2a.task import TaskManager


# Singleton registry — in production, backed by Redis.
_registry = AgentRegistry()


@get("/v1/a2a/agents", status_code=200)
async def list_a2a_agents(tenant_id: str = "default") -> dict:
    """List all registered A2A agents for ``tenant_id``."""
    agents = await _registry.list_agents(tenant_id)
    return {"agents": [a.to_dict() for a in agents]}


@post("/v1/a2a/agents/register", status_code=201)
async def register_a2a_agent(data: dict) -> dict:
    """Register a new A2A agent."""
    skills_raw = data.get("skills", [])
    skills: list[Skill] = []
    for s in skills_raw:
        if isinstance(s, dict):
            skills.append(Skill.from_dict(s))
        elif isinstance(s, str):
            skills.append(Skill(id=s, name=s, description=""))

    card = AgentCard(
        name=data.get("name", "unknown"),
        description=data.get("description", ""),
        version=data.get("version", "1.0.0"),
        url=data.get("url", ""),
        capabilities=list(data.get("capabilities", [])),
        skills=skills,
        authentication=data.get("authentication"),
        tenant_id=data.get("tenant_id", "default"),
    )
    await _registry.register(card)
    return {"status": "registered", "agent": card.name}


# Build a default A2AServer (no handler configured -> echo mode) and mount its
# JSON-RPC + REST + SSE endpoints. In production this is replaced with a real
# agent handler; the server is constructed lazily to avoid import-time work.
from ai_a2a.server import A2AServer  # noqa: E402

_default_card = AgentCard(
    name="ai-platform",
    description="EP AI Middle-Platform default A2A agent",
    version="1.0.0",
    url="",
)
_a2a_server = A2AServer(_default_card, TaskManager())
_a2a_router = create_a2a_router(_a2a_server)

a2a_router = Router(
    path="/",
    route_handlers=[list_a2a_agents, register_a2a_agent, _a2a_router],
)
