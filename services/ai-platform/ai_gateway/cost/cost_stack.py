"""SAD §5 / SDD §3.4 — CostStack orchestrator.

Wires the 4 cost-stack layers together in the irreversible order defined by
SAD §5:

    ① semantic cache     — hit ⇒ return immediately
    ② route router       — classify prompt → pick model family
    ③ confidence cascade — only for "simple" small-model responses; low
                            confidence ⇒ escalate to flagship
    ④ provider fallback  — chain of providers for the chosen model

The final response (cache hit or freshly produced) is written back to the
cache so subsequent identical requests hit Layer 1.
"""

from __future__ import annotations

import structlog

from ..models import ChatRequest, ChatResponse
from .confidence_cascade import ConfidenceCascade
from .route_router import FLAGSHIP_MODEL, RouteRouter
from .semantic_cache import SemanticCache
from .provider_fallback import ProviderFallback

logger = structlog.get_logger(__name__)


class CostStack:
    """Orchestrate the 4-layer cost stack."""

    def __init__(
        self,
        cache: SemanticCache,
        router: RouteRouter,
        cascade: ConfidenceCascade,
        fallback: ProviderFallback,
    ) -> None:
        self._cache = cache
        self._router = router
        self._cascade = cascade
        self._fallback = fallback

    def execute(
        self,
        request: ChatRequest,
        providers: list,
        flagship_providers: list,
    ) -> tuple[ChatResponse, dict]:
        """Run the cost stack for ``request``.

        ``providers`` is the small-model provider chain (used for "simple"
        prompts). ``flagship_providers`` is the flagship-model chain used for
        "complex" prompts and for cascade escalation.

        Returns ``(response, metadata)`` where ``metadata`` describes which
        layer produced the answer, e.g.::

            {"layer": "cache", "cached": True}
            {"layer": "router", "model": "gpt-4o-mini", "classified": "simple"}
            {"layer": "cascade", "escalated": True, "original_model": "gpt-4o-mini"}
            {"layer": "router", "model": "gpt-4o", "classified": "complex"}
        """
        # ① Layer 1 — semantic cache (hit short-circuits everything).
        cached = self._cache.get(request.messages)
        if cached is not None:
            logger.info("cost_stack: cache hit", hash=SemanticCache.hash(request.messages))
            return cached, {"layer": "cache", "cached": True}

        # ② Layer 2 — route router picks a model family.
        classification = self._router.classify(request.messages)
        model = self._router.route(request.messages)
        request.model = model
        logger.info("cost_stack: routed", classified=classification, model=model)

        if classification == "complex":
            # Complex prompts go straight to the flagship model — cascade is
            # skipped (irreversible order: cascade only runs on small-model
            # outputs).
            response, _used = self._fallback.call(request, flagship_providers)
            self._cache.put(request.messages, response)
            return response, {"layer": "router", "model": model, "classified": "complex"}

        # ③ + ④ — simple: try small model, then cascade to flagship if needed.
        response, _used = self._fallback.call(request, providers)

        if self._cascade.should_escalate(response):
            logger.info("cost_stack: cascading to flagship", original_model=model)
            request.model = FLAGSHIP_MODEL
            flagship_response, _used = self._fallback.call(request, flagship_providers)
            self._cache.put(request.messages, flagship_response)
            return flagship_response, {
                "layer": "cascade",
                "escalated": True,
                "original_model": model,
            }

        # High-confidence small-model answer — cheapest path.
        self._cache.put(request.messages, response)
        return response, {"layer": "router", "model": model, "classified": "simple"}
