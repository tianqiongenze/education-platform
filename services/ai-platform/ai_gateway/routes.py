"""Litestar routes for the AI gateway."""

from __future__ import annotations

from litestar import Router, get, post
from litestar.exceptions import HTTPException

from .config import config
from .mock_llm import MockLLMProvider
from .models import ChatRequest, ChatResponse, EmbeddingRequest, EmbeddingResponse

# D-046: register the new agent / A2A / MCP-tool / plugin / memory route groups.
from .agent_routes import agent_router
from .a2a_routes import a2a_router
from .tool_routes import tool_router
from .plugin_routes import plugin_router
from .memory_routes import memory_router


def get_provider():
    if config.llm_mode == "mock":
        return MockLLMProvider()
    # Future: real provider
    return MockLLMProvider()


_provider = get_provider()


@post("/v1/chat/completions", status_code=200)
async def chat_completions(data: ChatRequest) -> ChatResponse:
    try:
        return _provider.chat(data)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@post("/v1/embeddings", status_code=200)
async def embeddings(data: EmbeddingRequest) -> EmbeddingResponse:
    return _provider.embed(data)


@get("/health")
async def health() -> dict:
    return {"status": "ok", "service": "ai-gateway", "llm_mode": config.llm_mode}


router = Router(
    path="/",
    route_handlers=[
        chat_completions,
        embeddings,
        health,
        agent_router,
        a2a_router,
        tool_router,
        plugin_router,
        memory_router,
    ],
)
