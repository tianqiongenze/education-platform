"""Gateway middleware built on Litestar built-ins.

Replaces the former hand-written ASGI logging + rate-limit middlewares with:
- ``LoggingMiddlewareConfig`` from ``litestar.middleware.logging`` for structured request logging.
- ``RateLimitConfig`` from ``litestar.middleware.rate_limit`` for per-IP rate limiting.

Both are configured in ``ai_gateway.app.create_app``. This module keeps a thin
re-export so the app factory reads cleanly and so tests can import the config
objects if needed.
"""

from __future__ import annotations

import os
import secrets
from typing import Awaitable, Callable, MutableMapping

from litestar.middleware.logging import LoggingMiddlewareConfig
from litestar.middleware.rate_limit import RateLimitConfig

# Rate limit — configurable via env for benchmark vs production.
# Default 100 req/min for safety; EP_RATE_LIMIT_PER_MIN env overrides for load tests.
_default_rate = int(os.getenv("EP_RATE_LIMIT_PER_MIN", "100"))
rate_limit_config = RateLimitConfig(rate_limit=("minute", _default_rate))

# Structured request logging: method, path, status, duration.
logging_middleware_config = LoggingMiddlewareConfig()


# D-133: W3C TraceContext fallback for when the OTel SDK is not installed.
#
# When opentelemetry-instrumentation-asgi is unavailable (e.g. dev/test
# without tracing deps), the OpenTelemetryASGIMiddleware is omitted from the
# stack and the trace chain breaks at the AI service boundary even though
# ws-gateway now propagates traceparent (D-133). This pure-Python ASGI
# middleware ensures the inbound ``traceparent`` header is at least forwarded
# on outbound httpx calls made within the request scope, and echoed on the
# response, so log/metric correlation across services keeps working.
#
# It does NOT emit OTLP spans — that still requires the OTel SDK. When the
# SDK IS available, app.create_app() prefers OpenTelemetryASGIMiddleware and
# skips this fallback (the SDK middleware is a strict superset).
TRACEPARENT_HEADER = "traceparent"


def _new_traceparent() -> str:
    """Generate a fresh W3C traceparent (version 00, sampled).

    Format: ``00-<traceId(32hex)>-<spanId(16hex)>-01``.
    Mirrors the logic in ws-gateway src/middleware/otel.go.
    """
    trace_id = secrets.token_hex(16)  # 16 bytes -> 32 hex chars
    span_id = secrets.token_hex(8)  # 8 bytes -> 16 hex chars
    return f"00-{trace_id}-{span_id}-01"


class TraceContextASGIMiddleware:
    """Pure-Python ASGI middleware that propagates the W3C traceparent.

    Extracts ``traceparent`` from the inbound request headers (HTTP scope),
    generating a new one if absent, and stores it back into the scope's
    request headers so downstream code (and httpx instrumentation, if later
    enabled) sees a consistent value. The header is also appended to the
    response headers so callers can correlate.

    Implemented as a raw ASGI middleware (pure ASGI 3 callable) so it works
    under any ASGI framework — Litestar accepts raw middleware classes in the
    ``middleware=`` list alongside Litestar config objects.
    """

    def __init__(self, app: Callable) -> None:
        self.app = app

    async def __call__(
        self,
        scope: MutableMapping,
        receive: Callable[[], Awaitable],
        send: Callable[[MutableMapping], Awaitable],
    ) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return

        headers = scope.setdefault("headers", [])
        # ASGI headers are a list of (name, value) byte tuples (lowercased).
        existing = None
        for name, value in headers:
            if name == TRACEPARENT_HEADER.encode("latin-1"):
                existing = value.decode("latin-1")
                break

        traceparent = existing if existing else _new_traceparent()
        if not existing:
            headers.append(
                (TRACEPARENT_HEADER.encode("latin-1"), traceparent.encode("latin-1"))
            )

        async def send_wrapper(message: MutableMapping) -> None:
            if message.get("type") == "http.response.start":
                resp_headers = message.setdefault("headers", [])
                resp_headers.append(
                    (
                        TRACEPARENT_HEADER.encode("latin-1"),
                        traceparent.encode("latin-1"),
                    )
                )
            await send(message)

        await self.app(scope, receive, send_wrapper)


__all__ = [
    "rate_limit_config",
    "logging_middleware_config",
    "TraceContextASGIMiddleware",
]
