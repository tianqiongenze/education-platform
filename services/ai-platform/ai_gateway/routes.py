"""Litestar routes for the AI gateway."""

from __future__ import annotations

from litestar import Router, get, post
from litestar.exceptions import HTTPException
from litestar.response import Response

from .config import config
from .cost.confidence_cascade import ConfidenceCascade
from .cost.cost_stack import CostStack
from .cost.provider_fallback import ProviderFallback
from .cost.route_router import RouteRouter
from .cost.semantic_cache import SemanticCache
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


def _build_cost_stack() -> CostStack:
    """Lazily build the module-level cost stack singleton.

    The semantic cache degrades gracefully when Redis is unavailable, so this
    never raises — the stack is always usable (cache layer becomes a no-op).
    """
    return CostStack(
        cache=SemanticCache(),
        router=RouteRouter(),
        cascade=ConfidenceCascade(),
        fallback=ProviderFallback(timeout_sec=float(config.llm_timeout_sec)),
    )


# Module-level singleton — built once on import. Redis may be down at import
# time; the cache layer handles that (no-op) and the stack stays functional.
_cost_stack = _build_cost_stack()


def _providers_for(model_hint: str | None) -> list[MockLLMProvider]:
    """Build a provider chain for the (small) model family.

    Today both primary and fallback are MockLLMProvider instances. When real
    providers land, this will return the small-model chain (e.g. qwen-small
    primary + openai-mini fallback). ``model_hint`` is accepted for future
    per-model selection but currently unused.
    """
    # Two distinct instances so the fallback chain is realistic (a stateful
    # provider could fail without affecting the secondary instance).
    return [MockLLMProvider(), MockLLMProvider()]


def _flagship_providers_for() -> list[MockLLMProvider]:
    """Build the flagship provider chain (currently MockLLMProvider)."""
    return [MockLLMProvider(), MockLLMProvider()]


@post("/v1/chat/completions", status_code=200)
async def chat_completions(data: ChatRequest) -> Response[ChatResponse]:
    try:
        providers = _providers_for(data.model)
        flagship_providers = _flagship_providers_for()
        response, meta = _cost_stack.execute(data, providers, flagship_providers)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    # Expose cost-stack metadata as response headers for observability /
    # debugging. Header values must be strings.
    headers = {
        "X-Cost-Stack-Layer": str(meta.get("layer", "unknown")),
        "X-Cost-Stack-Cached": str(meta.get("cached", False)).lower(),
        "X-Cost-Stack-Escalated": str(meta.get("escalated", False)).lower(),
        "X-Cost-Stack-Model": str(meta.get("model", response.model)),
    }
    if "classified" in meta:
        headers["X-Cost-Stack-Classified"] = str(meta["classified"])
    if "original_model" in meta:
        headers["X-Cost-Stack-Original-Model"] = str(meta["original_model"])

    return Response(content=response, headers=headers)


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
