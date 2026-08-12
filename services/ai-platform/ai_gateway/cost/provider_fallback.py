"""SAD §5 Layer 4 — Multi-provider fallback chain.

Each provider is a callable ``(ChatRequest) -> ChatResponse``. The fallback
tries providers in order; the first that succeeds wins. On total failure the
last exception is re-raised so the caller can map it to an HTTP error.

This layer is intentionally provider-agnostic: it knows nothing about which
LLM vendor a provider talks to — that mapping lives in the route router and
the providers list the caller builds.
"""

from __future__ import annotations

import logging
from typing import Callable

import structlog

from ..models import ChatRequest, ChatResponse

logger = structlog.get_logger(__name__)

# A provider callable carries a name (for logging / metrics) and a chat fn.
Provider = Callable[[ChatRequest], ChatResponse]


class ProviderFallback:
    """Layer 4 — try a chain of providers, escalating on failure."""

    def __init__(self, timeout_sec: float = 30.0) -> None:
        self._timeout_sec = timeout_sec

    @staticmethod
    def _provider_name(provider: object) -> str:
        # Prefer an explicit ``name`` attr, then the class name, then repr.
        name = getattr(provider, "name", None)
        if isinstance(name, str) and name:
            return name
        cls = getattr(provider, "__class__", None)
        if cls is not None:
            return cls.__name__
        return repr(provider)

    def call(self, request: ChatRequest, providers: list) -> tuple[ChatResponse, str]:
        """Call each provider in order; return (response, provider_name_used).

        ``providers`` is a list of either:
          * bare callables ``(ChatRequest) -> ChatResponse``, or
          * objects with a ``.chat(ChatRequest) -> ChatResponse`` method.

        Raises the last exception if every provider fails.
        """
        if not providers:
            raise ValueError("provider_fallback: providers list is empty")

        last_exc: Exception | None = None
        for idx, provider in enumerate(providers):
            name = self._provider_name(provider)
            chat_fn = provider if callable(provider) else getattr(provider, "chat", None)
            if chat_fn is None or not callable(chat_fn):
                logger.error("provider_fallback: provider has no callable interface", provider=name)
                last_exc = TypeError(f"provider {name!r} is not callable")
                continue
            try:
                response = chat_fn(request)
            except Exception as exc:  # noqa: BLE001 — any failure triggers fallback
                last_exc = exc
                logger.warning(
                    "provider_fallback: provider failed, trying next",
                    provider=name,
                    index=idx,
                    error=str(exc),
                )
                continue
            if idx > 0:
                logger.info("provider_fallback: recovered via fallback", provider=name, index=idx)
            else:
                logger.debug("provider_fallback: primary succeeded", provider=name)
            return response, name

        # Every provider failed — re-raise the last error.
        assert last_exc is not None  # for type checkers; providers was non-empty
        raise last_exc
