"""D-216 concurrency fixes — regression locks.

Covers:
  * P1-3: JWT fail-closed in production (missing JWT_PUBLIC_KEY aborts startup)
  * P0-7: chat_completions offloads the sync cost stack via asyncio.to_thread
  * P1-8: agent run passes per-call limits (no global executor mutation)
"""

from __future__ import annotations

import asyncio
import os
from unittest import mock

import pytest


# ---------------------------------------------------------------- P1-3 fail-closed


def test_jwt_middleware_fails_closed_in_production(monkeypatch):
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.delenv("JWT_PUBLIC_KEY", raising=False)
    from ai_gateway.middleware.jwt_auth import JWTAuthMiddleware

    with pytest.raises(RuntimeError, match="JWT_PUBLIC_KEY"):
        JWTAuthMiddleware(app=lambda scope, receive, send: None)


def test_jwt_middleware_permissive_outside_production(monkeypatch):
    monkeypatch.setenv("APP_ENV", "dev")
    monkeypatch.delenv("JWT_PUBLIC_KEY", raising=False)
    from ai_gateway.middleware.jwt_auth import JWTAuthMiddleware

    JWTAuthMiddleware(app=lambda scope, receive, send: None)  # must not raise


# ---------------------------------------------------------------- P0-7 to_thread


def test_chat_completions_uses_to_thread(monkeypatch):
    """The sync cost-stack execute must run in a worker thread, not on the loop."""
    from ai_gateway import routes as routes_mod

    called = {}

    class _FakeResp:
        model = "mock"

    class _FakeCostStack:
        def execute(self, data, providers, flagship):
            called["thread"] = threading.current_thread().name
            called["args"] = (type(data).__name__, len(providers), len(flagship))
            return _FakeResp(), {"layer": "test"}

    class _FakeProvider:
        pass

    fake_stack = _FakeCostStack()
    monkeypatch.setattr(routes_mod, "_cost_stack", fake_stack)
    monkeypatch.setattr(
        routes_mod, "_providers_for", lambda model: [_FakeProvider()]
    )
    monkeypatch.setattr(
        routes_mod, "_flagship_providers_for", lambda: [_FakeProvider()]
    )

    captured = {}

    real_to_thread = asyncio.to_thread

    async def spy_to_thread(fn, *a, **kw):
        captured["offloaded"] = fn.__self__ is fake_stack if hasattr(fn, "__self__") else True
        return await real_to_thread(fn, *a, **kw)

    monkeypatch.setattr(routes_mod.asyncio, "to_thread", spy_to_thread)

    from ai_gateway.models import ChatRequest

    req = ChatRequest(messages=[{"role": "user", "content": "hi"}])

    async def call_route():
        handler = routes_mod.chat_completions
        fn = getattr(handler, "fn", handler)  # Litestar wraps with @post
        return await fn(req)

    resp = asyncio.run(call_route())
    assert captured.get("offloaded") is True, "route must offload via to_thread"
    assert called["args"][0] == "ChatRequest"


# ---------------------------------------------------------------- P1-8 per-call limits


@pytest.mark.asyncio
async def test_agent_executor_per_call_limits():
    """Per-call limits override without mutating the executor singleton."""
    from ai_harness.executor import AgentExecutor, ExecutionLimits

    ex = AgentExecutor(limits=ExecutionLimits(max_time_sec=120))

    class _Graph:
        async def ainvoke(self, state, config=None):
            return {"ok": True}

    await ex.execute(_Graph(), {"messages": []}, limits=ExecutionLimits(max_time_sec=5))
    # singleton untouched by the per-call override
    assert ex.limits.max_time_sec == 120


import threading  # noqa: E402  (used inside test bodies above)
