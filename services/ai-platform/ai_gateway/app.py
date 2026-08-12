"""Litestar application factory."""

from __future__ import annotations

import logging
import os

import structlog
from litestar import Litestar
from litestar.config.cors import CORSConfig
from litestar.exceptions import HTTPException
from litestar.status_codes import HTTP_400_BAD_REQUEST

# D-118: OpenTelemetry ASGI middleware. Imported conditionally so the app
# boots even when the OTel SDK is not installed (dev/test without tracing).
# When unavailable, OTel middleware is omitted from the stack.
try:
    from opentelemetry.instrumentation.asgi import OpenTelemetryASGIMiddleware as _OTelMiddleware
except ImportError:
    _OTelMiddleware = None

# D-133: TraceContextASGIMiddleware + Litestar config objects live in the
# ``middleware.py`` module (sibling to the ``middleware/`` package).
# Import directly from the module to avoid the package __init__.
from .middleware_base import (
    TraceContextASGIMiddleware,
    logging_middleware_config,
    rate_limit_config,
)
# D-139 P1-5: JWT Bearer auth middleware. Imported from the
# ``ai_gateway.middleware`` sub-package (NOT the sibling ``middleware.py``
# module) so the two coexist: ``middleware.py`` holds Litestar config
# objects + the W3C trace fallback; the ``middleware/`` package holds the
# new JWT auth middleware and any future middlewares.
from .middleware.jwt_auth import JWTAuthMiddleware
from .observability import setup_telemetry, shutdown_telemetry
from .routes import router

ALLOWED_ORIGINS = os.getenv("CORS_ALLOW_ORIGINS", "http://localhost:3000,http://localhost:5173").split(",")


def _configure_structlog() -> None:
    structlog.configure(
        processors=[
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(),
    )


def _exception_handler(request, exc: HTTPException):
    from litestar.response import Response

    status = exc.status_code if hasattr(exc, "status_code") else HTTP_400_BAD_REQUEST
    return Response(content={"detail": str(exc.detail) if hasattr(exc, "detail") else str(exc)}, status_code=status)


def create_app() -> Litestar:
    _configure_structlog()

    # D-118: initialize OpenTelemetry tracing. Failure is non-fatal — the
    # service continues untraced (no-op tracer). Litestar is ASGI-compatible,
    # so OpenTelemetryASGIMiddleware instruments every inbound request as a
    # server span and propagates the W3C traceparent header downstream.
    setup_telemetry()
    # Instrument outbound httpx calls so traces continue to LLM/vector-DB.
    try:
        from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

        HTTPXClientInstrumentor().instrument()
    except Exception as exc:  # noqa: BLE001 — best-effort, never fatal
        logging.getLogger(__name__).warning(
            "otel: httpx instrumentation skipped: %s", exc
        )

    cors = CORSConfig(
        allow_origins=ALLOWED_ORIGINS,
        allow_methods=["*"],
        allow_headers=["Authorization", "Content-Type", "X-Requested-With", "X-Role", "X-Tenant-Id", "traceparent", "X-Request-Id"],
    )

    # D-118 / D-133 / D-139: build the middleware stack. When the OTel SDK is
    # available, OpenTelemetryASGIMiddleware instruments every inbound request
    # as a server span AND propagates traceparent (strict superset). When the
    # SDK is NOT installed, fall back to TraceContextASGIMiddleware so the W3C
    # traceparent header still propagates (D-133) — keeping cross-service
    # trace correlation alive without the SDK.
    #
    # D-139 P1-5: JWTAuthMiddleware is layered AFTER tracing (so a 401 still
    # gets a server span / traceparent) and BEFORE logging/rate-limit (so
    # rejected requests are still logged and do not consume the rate budget
    # of authenticated users). It is a raw ASGI middleware and composes with
    # Litestar's config-object middleware below.
    middleware_stack = [logging_middleware_config.middleware, rate_limit_config.middleware]
    if _OTelMiddleware is not None:
        middleware_stack.insert(0, _OTelMiddleware)
    else:
        middleware_stack.insert(0, TraceContextASGIMiddleware)
    middleware_stack.insert(1, JWTAuthMiddleware)

    app = Litestar(
        route_handlers=[router],
        cors_config=cors,
        middleware=middleware_stack,
        exception_handlers={HTTPException: _exception_handler},
        # D-118: flush pending spans on shutdown so in-flight traces are not dropped.
        on_shutdown=[_shutdown_telemetry_hook],
    )
    return app


def _shutdown_telemetry_hook(app: Litestar) -> None:  # noqa: ARG001
    """Litestar on_shutdown callable wrapper — ignores the app argument."""
    shutdown_telemetry()


app = create_app()
