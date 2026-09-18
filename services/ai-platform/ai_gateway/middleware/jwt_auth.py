"""JWT authentication middleware for AI Gateway (D-139 P1-5).

Validates JWT Bearer tokens on protected endpoints. Public endpoints
(``/health``, ``/healthz``, ``/docs``, ``/schema``, ``/favicon.ico``) are
exempt so health checks and OpenAPI exploration continue to work without
authentication.

Token validation uses RS256 signatures; the public key is read from the
``JWT_PUBLIC_KEY`` environment variable (PEM-encoded). When the variable is
unset the middleware runs in ``permissive`` mode: it still parses the
Bearer token (if present) and injects ``user_id``/``tenant_id`` into
``request.state``, but it does NOT reject requests that lack a token. This
keeps local dev/test green without forcing every developer to mint a JWT,
while production deployments set ``JWT_PUBLIC_KEY`` to enforce strict
validation.

Implementation notes:
  * Implemented as a raw ASGI 3 middleware (pure-Python, framework-agnostic)
    so it composes cleanly with the existing ``TraceContextASGIMiddleware``
    and Litestar's built-in logging / rate-limit middleware in
    ``ai_gateway.middleware``.
  * PyJWT is the ONLY accepted JWT backend. It is a hard runtime dependency
    for signature verification. If PyJWT is not installed, the middleware
    raises ``RuntimeError`` at construction time rather than silently
    degrading to an insecure decode-only path. (P1-5: removed the previous
    jwt-minimal / decode-only fallbacks.)
"""

from __future__ import annotations

import json
import logging
import os
import secrets
from typing import Any, Awaitable, Callable, MutableMapping

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

#: Endpoints exempt from JWT validation. Matched as exact path equality OR
#: path prefix (so ``/docs/oauth2-redirect`` is also exempt). Kept short and
#: explicit so reviewers can audit the public surface at a glance.
PUBLIC_PATHS: tuple[str, ...] = (
    "/health",
    "/healthz",
    "/ready",
    "/docs",
    "/schema",
    "/favicon.ico",
    "/openapi.json",  # Litestar serves the OpenAPI schema here
)

#: Header name carrying the Bearer token (RFC 6750).
AUTH_HEADER = b"authorization"

#: Bearer scheme prefix (case-insensitive on decode).
BEARER_PREFIX = b"bearer "

#: PEM-encoded RSA public key used to verify RS256 signatures. When unset
#: the middleware runs in permissive mode (see module docstring).
_PUBLIC_KEY_ENV = "JWT_PUBLIC_KEY"


def _is_public_path(path: str) -> bool:
    """Return True if ``path`` is exempt from JWT validation."""
    if not path:
        return False
    for pub in PUBLIC_PATHS:
        if path == pub or path.startswith(pub + "/"):
            return True
    return False


# ---------------------------------------------------------------------------
# JWT backend — PyJWT is the only accepted backend.
# ---------------------------------------------------------------------------


def _require_pyjwt() -> Any:
    """Import and return the PyJWT module, raising RuntimeError if absent.

    P1-5: signature verification MUST NOT silently degrade to a decode-only
    fallback. PyJWT is a hard runtime requirement for the JWT backend.
    """
    try:
        import jwt as _pyjwt  # type: ignore[import-not-found]

        # PyJWT exposes .decode with algorithms kwarg and PyJWKClient.
        if hasattr(_pyjwt, "decode") and hasattr(_pyjwt, "PyJWKClient"):
            return _pyjwt
    except ImportError:
        pass
    raise RuntimeError(
        "PyJWT is required for JWT verification but is not installed. "
        "Install with: pip install PyJWT"
    )


class _JwtBackend:
    """Thin facade over PyJWT.

    ``verify(token, public_key_pem)`` returns the decoded payload dict on
    success and raises ``ValueError`` on any validation failure. PyJWT is
    the only accepted backend; if it is not installed, constructing the
    backend raises ``RuntimeError`` (P1-5: no decode-only fallback).
    """

    def __init__(self) -> None:  # noqa: ANN204 — simple init
        # PyJWT is mandatory — fail fast and loudly if missing.
        _pyjwt = _require_pyjwt()
        self._pyjwt = _pyjwt
        self._verify_fn: Callable[..., dict[str, Any]] = lambda token, key: _pyjwt.decode(
            token,
            key,
            algorithms=["RS256"],
            options={"require": ["exp", "iat", "sub"]},
        )
        self._mode: str = "pyjwt"

    @property
    def mode(self) -> str:
        return self._mode

    def verify(self, token: str, public_key_pem: str | None) -> dict[str, Any]:
        """Verify ``token`` and return the payload.

        When ``public_key_pem`` is None the backend MUST refuse to verify
        (there is no decode-only fallback — P1-5).
        """
        if not public_key_pem:
            raise ValueError("JWT_PUBLIC_KEY is not set; cannot verify signature")
        return self._verify_fn(token, public_key_pem)


# Module-singleton backend — constructed once at import time.
_BACKEND = _JwtBackend()


def get_jwt_backend() -> _JwtBackend:
    """Return the module JWT backend (for tests / introspection)."""
    return _BACKEND


# ---------------------------------------------------------------------------
# ASGI middleware
# ---------------------------------------------------------------------------


def _extract_bearer(headers: list[tuple[bytes, bytes]]) -> str | None:
    """Return the Bearer token from ASGI headers, or None if absent/invalid."""
    for name, value in headers:
        if name != AUTH_HEADER:
            continue
        lowered = value.lower()
        if not lowered.startswith(BEARER_PREFIX):
            return None
        # Strip "bearer " prefix (original-case, after the prefix).
        return value[len(BEARER_PREFIX):].decode("latin-1").strip()
    return None


def _unauthorized_response(scope: MutableMapping, message: str) -> tuple[bytes, int]:
    """Build a 401 JSON response body. Returns (body_bytes, status)."""
    body = json.dumps({"detail": message}).encode("utf-8")
    return body, 401


class JWTAuthMiddleware:
    """ASGI 3 middleware that validates JWT Bearer tokens.

    Composes with the existing ``TraceContextASGIMiddleware`` and Litestar's
    logging / rate-limit middleware. Registered in ``ai_gateway.app`` after
    the trace-context middleware so ``request.state.user_id`` is available
    to all downstream handlers.
    """

    def __init__(self, app: Callable) -> None:
        self.app = app
        # Snapshot the public key at construction time so a mid-flight env
        # change does not flip the middleware between strict and permissive
        # mode within a single process lifetime.
        self._public_key: str | None = os.getenv(_PUBLIC_KEY_ENV)
        if not self._public_key:
            # D-216 P1-3 fail-closed: in production a missing key must abort
            # startup (mirrors ws-gateway src/failclosed.go). A silent
            # permissive fallback would leave the whole site anonymously
            # open after a config mistake.
            if os.getenv("APP_ENV", "").lower() == "production":
                raise RuntimeError(
                    "JWT_PUBLIC_KEY is not set but APP_ENV=production — "
                    "refusing to start in permissive mode (fail-closed). "
                    "Set JWT_PUBLIC_KEY (PEM) for this deployment."
                )
            logger.info(
                "D-139 P1-5: %s not set — JWTAuthMiddleware running in "
                "permissive mode (tokens parsed but not required). Set it in "
                "production to enforce RS256 verification.",
                _PUBLIC_KEY_ENV,
            )

    async def __call__(
        self,
        scope: MutableMapping,
        receive: Callable[[], Awaitable],
        send: Callable[[MutableMapping], Awaitable],
    ) -> None:
        # Only intercept HTTP; websockets and lifespan pass through untouched.
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return

        path: str = scope.get("path", "") or ""
        if _is_public_path(path):
            await self.app(scope, receive, send)
            return

        headers: list[tuple[bytes, bytes]] = scope.setdefault("headers", [])
        token = _extract_bearer(headers)

        strict = bool(self._public_key)

        if token is None:
            if strict:
                await self._reject(send, "missing or invalid Authorization header")
                return
            # Permissive mode: continue without user identity.
            await self.app(scope, receive, send)
            return

        # We have a token — verify its RS256 signature. In strict mode the
        # public key is set; in permissive mode the public key is None and
        # verify() raises ValueError, which is caught below as a 401.
        try:
            payload = _BACKEND.verify(token, self._public_key)
        except Exception as exc:  # noqa: BLE001 — any verification failure is a 401
            await self._reject(send, f"JWT verification failed: {exc}")
            return

        # Inject user identity into request.state so downstream handlers can
        # read request.state.user_id / request.state.tenant_id without
        # re-parsing the token. Litestar exposes request.state via the ASGI
        # scope's "state" key.
        state = scope.setdefault("state", {})
        state["user_id"] = payload.get("sub") or payload.get("user_id")
        state["tenant_id"] = payload.get("tenant_id") or payload.get("tid")
        state["jwt_claims"] = payload

        await self.app(scope, receive, send)

    async def _reject(self, send: Callable[[MutableMapping], Awaitable], message: str) -> None:
        """Emit a 401 JSON response without invoking the downstream app."""
        body, status = _unauthorized_response({}, message)
        await send(
            {
                "type": "http.response.start",
                "status": status,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode("latin-1")),
                    # D-139: nonce for any browser-facing error page.
                    (b"x-request-id", secrets.token_hex(8).encode("latin-1")),
                ],
            }
        )
        await send(
            {
                "type": "http.response.body",
                "body": body,
            }
        )


__all__ = [
    "JWTAuthMiddleware",
    "PUBLIC_PATHS",
    "get_jwt_backend",
]
