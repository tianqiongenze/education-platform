"""Tests for ai_gateway.middleware.jwt_auth (D-139 P1-5).

Covers:
  * PyJWT is the only accepted backend — RuntimeError when PyJWT is absent.
  * The ASGI middleware rejects unauthenticated requests in strict mode and
    passes them through in permissive mode.
  * ``verify()`` refuses to run without a public key (no decode-only fallback).
"""

from __future__ import annotations

import builtins
import importlib
import json
import os
from typing import Any
from unittest import mock

import pytest


def _reload_jwt_auth() -> Any:
    """Force a fresh import of the jwt_auth middleware module."""
    import ai_gateway.middleware.jwt_auth as mod

    return importlib.reload(mod)


def test_backend_raises_runtime_error_when_pyjwt_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    """P1-5: constructing the backend without PyJWT must raise RuntimeError."""
    real_import = builtins.__import__

    def _blocking_import(name: str, *args: Any, **kwargs: Any) -> Any:
        if name == "jwt" or name.startswith("jwt."):
            raise ImportError("simulated: PyJWT not installed")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", _blocking_import)

    # The module constructs its singleton _BACKEND at import time, so the
    # RuntimeError is raised during reload. Importing the jwt_auth module
    # directly triggers it. Use a nested pytest.raises so the import-time
    # RuntimeError is captured as the test outcome.
    import sys

    # Drop cached modules so the blocking import is honored on reload.
    for k in list(sys.modules):
        if k == "ai_gateway.middleware.jwt_auth" or k.startswith("ai_gateway.middleware.jwt_auth."):
            del sys.modules[k]

    with pytest.raises(RuntimeError, match="PyJWT is required"):
        importlib.import_module("ai_gateway.middleware.jwt_auth")


def test_backend_mode_is_pyjwt() -> None:
    """With PyJWT installed, the backend reports mode == 'pyjwt'."""
    import jwt  # noqa: F401  — ensure dependency is present

    mod = _reload_jwt_auth()
    backend = mod.get_jwt_backend()
    assert backend.mode == "pyjwt"


def test_verify_refuses_without_public_key() -> None:
    """P1-5: verify() must raise ValueError when no public key is set."""
    import jwt  # noqa: F401

    mod = _reload_jwt_auth()
    backend = mod.get_jwt_backend()
    with pytest.raises(ValueError, match="JWT_PUBLIC_KEY is not set"):
        backend.verify("any.token.here", None)


@pytest.mark.asyncio
async def test_middleware_rejects_missing_token_in_strict_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    """Strict mode (JWT_PUBLIC_KEY set) -> 401 when no Authorization header."""
    import jwt  # noqa: F401

    monkeypatch.setenv("JWT_PUBLIC_KEY", "some-pem")
    mod = _reload_jwt_auth()

    captured: dict[str, Any] = {}

    async def app(scope: Any, receive: Any, send: Any) -> None:  # noqa: ANN401
        captured["called"] = True

    mw = mod.JWTAuthMiddleware(app)

    async def receive() -> dict[str, Any]:
        return {"type": "http.request"}

    async def send(message: dict[str, Any]) -> None:
        captured.setdefault("messages", []).append(message)

    scope = {"type": "http", "path": "/protected", "headers": []}
    await mw(scope, receive, send)

    assert not captured.get("called"), "downstream app must NOT be called on 401"
    starts = [m for m in captured["messages"] if m["type"] == "http.response.start"]
    assert starts and starts[0]["status"] == 401


@pytest.mark.asyncio
async def test_middleware_permissive_when_no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    """Permissive mode (no JWT_PUBLIC_KEY) -> request passes through without token."""
    import jwt  # noqa: F401

    monkeypatch.delenv("JWT_PUBLIC_KEY", raising=False)
    mod = _reload_jwt_auth()

    captured: dict[str, Any] = {}

    async def app(scope: Any, receive: Any, send: Any) -> None:  # noqa: ANN401
        captured["called"] = True

    mw = mod.JWTAuthMiddleware(app)

    async def receive() -> dict[str, Any]:
        return {"type": "http.request"}

    async def send(message: dict[str, Any]) -> None:
        pass

    scope = {"type": "http", "path": "/protected", "headers": []}
    await mw(scope, receive, send)
    assert captured.get("called") is True


@pytest.mark.asyncio
async def test_public_path_bypasses_auth(monkeypatch: pytest.MonkeyPatch) -> None:
    """Public paths (e.g. /health) bypass JWT validation even in strict mode."""
    import jwt  # noqa: F401

    monkeypatch.setenv("JWT_PUBLIC_KEY", "some-pem")
    mod = _reload_jwt_auth()

    captured: dict[str, Any] = {}

    async def app(scope: Any, receive: Any, send: Any) -> None:  # noqa: ANN401
        captured["called"] = True

    mw = mod.JWTAuthMiddleware(app)

    async def receive() -> dict[str, Any]:
        return {"type": "http.request"}

    async def send(message: dict[str, Any]) -> None:
        pass

    scope = {"type": "http", "path": "/health", "headers": []}
    await mw(scope, receive, send)
    assert captured.get("called") is True
