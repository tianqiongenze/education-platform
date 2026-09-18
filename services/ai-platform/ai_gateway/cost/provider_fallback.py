"""SAD §5 Layer 4 — Multi-provider fallback chain.

Each provider is a callable ``(ChatRequest) -> ChatResponse``. The fallback
tries providers in order; the first that succeeds wins. On total failure the
last exception is re-raised so the caller can map it to an HTTP error.

This layer is intentionally provider-agnostic: it knows nothing about which
LLM vendor a provider talks to — that mapping lives in the route router and
the providers list the caller builds.

D-216 P1-9: the async entry point :meth:`ProviderFallback.acall` adds the
concurrency control / backpressure that was previously missing (the old
``_timeout_sec`` was stored but never applied):

  * every provider call runs via ``asyncio.to_thread`` (keeping the sync
    call off the event loop) and is bounded by
    ``asyncio.wait_for(..., timeout=self._timeout_sec)`` — a hung or slow
    LLM call no longer stalls the request forever;
  * a global ``asyncio.Semaphore`` (default 8, env ``LLM_MAX_CONCURRENCY``)
    caps the total number of in-flight LLM calls — excess callers queue;
  * a per-provider ``asyncio.Semaphore`` (default 4, env
    ``LLM_PROVIDER_MAX_CONCURRENCY``) caps concurrent calls to the same
    provider so a single vendor outage cannot exhaust the global budget.

A timed-out or otherwise failed provider degrades down the existing fallback
chain (primary → secondary → ...); only when every provider fails is the
last exception re-raised (unchanged ProviderFallback semantics).
"""

from __future__ import annotations

import asyncio
import logging
import os
import threading
import weakref
from typing import Callable

import structlog

from ..models import ChatRequest, ChatResponse

logger = structlog.get_logger(__name__)

# A provider callable carries a name (for logging / metrics) and a chat fn.
Provider = Callable[[ChatRequest], ChatResponse]

# D-216 P1-9 — default concurrency caps (overridable via env, see
# _env_int below and ai_gateway.config.Settings).
DEFAULT_GLOBAL_CONCURRENCY = 8
DEFAULT_PROVIDER_CONCURRENCY = 4


def _env_int(name: str, default: int) -> int:
    """Read a positive int from env var ``name``, falling back to ``default``."""
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        logger.warning(
            "provider_fallback: invalid env value, using default",
            env=name,
            value=raw,
            default=default,
        )
        return default
    return value if value > 0 else default


class _LoopLocalSemaphore:
    """Per-event-loop ``asyncio.Semaphore`` cache (D-216 P1-9).

    asyncio primitives bind to the loop they first contend on, while the
    gateway's ``ProviderFallback`` singletons outlive any single loop (one
    loop per test client in the suite; exactly one loop per worker in
    production). The semaphore is therefore created lazily per running loop.
    With a single production loop the limit is effectively process-global,
    as intended; loops are keyed weakly so closed loops release their entry.
    """

    def __init__(self, value: int) -> None:
        self._value = value
        self._lock = threading.Lock()
        self._by_loop: "weakref.WeakKeyDictionary[asyncio.AbstractEventLoop, asyncio.Semaphore]" = (
            weakref.WeakKeyDictionary()
        )

    def current(self) -> asyncio.Semaphore:
        """Return the semaphore bound to the running event loop."""
        loop = asyncio.get_running_loop()
        with self._lock:
            sem = self._by_loop.get(loop)
            if sem is None:
                sem = asyncio.Semaphore(self._value)
                self._by_loop[loop] = sem
            return sem


class ProviderFallback:
    """Layer 4 — try a chain of providers, escalating on failure."""

    def __init__(
        self,
        timeout_sec: float = 30.0,
        global_max_concurrency: int | None = None,
        provider_max_concurrency: int | None = None,
    ) -> None:
        self._timeout_sec = timeout_sec
        # D-216 P1-9 — explicit args win (gateway passes its Settings values);
        # env vars configure standalone construction; defaults keep everything
        # bounded. Both caps feed asyncio.Semaphore instances per event loop.
        self._global_sem = _LoopLocalSemaphore(
            global_max_concurrency
            if global_max_concurrency is not None
            else _env_int("LLM_MAX_CONCURRENCY", DEFAULT_GLOBAL_CONCURRENCY)
        )
        self._provider_max = (
            provider_max_concurrency
            if provider_max_concurrency is not None
            else _env_int("LLM_PROVIDER_MAX_CONCURRENCY", DEFAULT_PROVIDER_CONCURRENCY)
        )
        self._provider_sems: dict[str, _LoopLocalSemaphore] = {}
        self._provider_sems_lock = threading.Lock()

    # ---------------------------------------------------------------- helpers

    def _provider_sem(self, name: str) -> _LoopLocalSemaphore:
        """Return the per-provider semaphore registry for ``name``."""
        with self._provider_sems_lock:
            sem = self._provider_sems.get(name)
            if sem is None:
                sem = _LoopLocalSemaphore(self._provider_max)
                self._provider_sems[name] = sem
            return sem

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

    async def _call_one(self, name: str, chat_fn: Callable, request: ChatRequest) -> ChatResponse:
        """Run a single provider call under the concurrency caps + timeout.

        ``asyncio.to_thread`` keeps the sync provider call off the event
        loop (D-216 P0-7); ``asyncio.wait_for`` bounds it so a hung call
        cannot stall the request (D-216 P1-9). Note the thread itself is not
        killable — on timeout the wrapper is cancelled and the fallback
        chain proceeds while the orphaned thread finishes in the background.
        """
        global_sem = self._global_sem.current()
        provider_sem = self._provider_sem(name).current()
        async with global_sem, provider_sem:
            return await asyncio.wait_for(
                asyncio.to_thread(chat_fn, request),
                timeout=self._timeout_sec,
            )

    # ----------------------------------------------------------------- public

    async def acall(self, request: ChatRequest, providers: list) -> tuple[ChatResponse, str]:
        """Async variant of :meth:`call` with timeout + concurrency control.

        Same fallback semantics as :meth:`call` — first provider that
        succeeds wins, any failure (including timeout) degrades to the next
        provider, and the last exception is re-raised if all fail.
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
                response = await self._call_one(name, chat_fn, request)
            except Exception as exc:  # noqa: BLE001 — any failure (incl. timeout) triggers fallback
                last_exc = exc
                logger.warning(
                    "provider_fallback: provider failed or timed out, trying next",
                    provider=name,
                    index=idx,
                    error=str(exc),
                    timeout_sec=self._timeout_sec,
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

    def call(self, request: ChatRequest, providers: list) -> tuple[ChatResponse, str]:
        """Call each provider in order; return (response, provider_name_used).

        ``providers`` is a list of either:
          * bare callables ``(ChatRequest) -> ChatResponse``, or
          * objects with a ``.chat(ChatRequest) -> ChatResponse`` method.

        Raises the last exception if every provider fails.

        Synchronous legacy path — kept for direct/test callers. It cannot
        impose the async timeout/semaphores (there is no event loop to
        wait on); route handlers must use the async stack entry point
        (CostStack.aexecute → acall) which enforces both (D-216 P1-9).
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
