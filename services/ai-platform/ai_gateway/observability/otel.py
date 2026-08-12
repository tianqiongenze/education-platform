"""OpenTelemetry tracing setup for ai-platform.

D-118: ai-platform previously had zero OTel instrumentation, which broke the
distributed trace chain at the AI service boundary. This module wires up the
OTLP exporter, TracerProvider, and W3C TraceContext propagator so spans from
ws-gateway / portal-bff continue through the AI service.

The Litestar ASGI app is instrumented via
``opentelemetry-instrumentation-asgi`` (Litestar is ASGI-compatible). Outbound
``httpx`` calls are instrumented via ``opentelemetry-instrumentation-httpx``
so traces propagate to downstream LLM / vector-DB calls.

Design notes
------------
* Failure to initialize tracing MUST NOT crash the service (D-118 constraint).
  ``setup_telemetry`` logs the error and returns ``None``; the global tracer
  remains the no-op tracer, so the app still serves traffic untraced.
* When the OTel SDK is not installed (e.g. dev/test without tracing), all
  functions are safe no-ops.
* Service name defaults to ``ai-platform`` and is overridable via
  ``OTEL_SERVICE_NAME``.
* The OTLP endpoint is read from ``OTEL_EXPORTER_OTLP_ENDPOINT`` (default
  ``http://otel-collector:4317``). Both gRPC (``grpc``) and HTTP protobuf
  (``http/protobuf``) protocols are supported via ``OTEL_EXPORTER_OTLP_PROTOCOL``.
* Tail sampling runs in the collector (D-035 rule 5), so this service exports
  100% of spans and lets the collector decide what to keep.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

logger = logging.getLogger(__name__)

# D-118: Conditional OTel imports — the SDK may not be installed in dev/test.
# When unavailable, setup_telemetry/shutdown_telemetry are safe no-ops.
try:
    from opentelemetry import trace as _trace
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
        OTLPSpanExporter as GRPCSpanExporter,
    )
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
        OTLPSpanExporter as HTTPSpanExporter,
    )
    from opentelemetry.propagate import set_global_textmap
    from opentelemetry.propagators.composite import CompositePropagator
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
    from opentelemetry.trace.propagation.tracecontext import (
        TraceContextTextMapPropagator,
    )

    _OTEL_AVAILABLE = True
except ImportError:
    _OTEL_AVAILABLE = False
    TracerProvider = None  # type: ignore[assignment,misc]

# Module-level holder so the app can flush/shutdown on exit.
_tracer_provider: Optional["TracerProvider"] = None


def _build_resource(service_name: str):
    """Build the OTel Resource describing this service instance."""
    attrs = {
        "service.name": service_name,
        "service.namespace": "education-platform",
        "service.version": os.getenv("SERVICE_VERSION", "1.0.0"),
        "deployment.environment": os.getenv("DEPLOYMENT_ENVIRONMENT", "production"),
    }
    # Merge any caller-supplied OTEL_RESOURCE_ATTRIBUTES (key=val,key=val).
    extra = os.getenv("OTEL_RESOURCE_ATTRIBUTES", "")
    if extra:
        for pair in extra.split(","):
            if "=" in pair:
                k, v = pair.split("=", 1)
                attrs[k.strip()] = v.strip()
    return Resource.create(attrs)


def setup_telemetry() -> Optional["TracerProvider"]:
    """Initialize OpenTelemetry tracing for ai-platform.

    Returns the created :class:`TracerProvider` (so the caller can register a
    shutdown hook) or ``None`` if initialization failed or the OTel SDK is not
    installed. Idempotent: a second call returns the existing provider.

    The function never raises — a collector outage must not take the AI
    service down (D-118 constraint).
    """
    global _tracer_provider
    if _tracer_provider is not None:
        return _tracer_provider

    if not _OTEL_AVAILABLE:
        logger.debug("otel: SDK not installed, tracing disabled (no-op)")
        return None

    service_name = os.getenv("OTEL_SERVICE_NAME", "ai-platform").strip() or "ai-platform"
    endpoint = os.getenv(
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317"
    ).strip()
    protocol = os.getenv("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc").strip().lower()

    try:
        resource = _build_resource(service_name)
        provider = TracerProvider(resource=resource)

        if protocol in ("http", "http/protobuf"):
            exporter = HTTPSpanExporter(endpoint=endpoint)
        else:
            # gRPC exporter expects host:port without scheme.
            grpc_endpoint = endpoint.replace("http://", "").replace("https://", "")
            exporter = GRPCSpanExporter(endpoint=grpc_endpoint, insecure=True)

        provider.add_span_processor(
            BatchSpanProcessor(
                exporter,
                max_export_batch_size=512,
                schedule_delay_millis=5000,
            )
        )

        _trace.set_tracer_provider(provider)
        # W3C TraceContext (traceparent/tracestate) + Baggage for cross-service
        # context attributes.
        set_global_textmap(
            CompositePropagator(
                [
                    TraceContextTextMapPropagator(),
                ]
            )
        )

        _tracer_provider = provider
        logger.info(
            "otel: tracer provider initialized (service=%s, endpoint=%s, protocol=%s)",
            service_name,
            endpoint,
            protocol,
        )
        return provider
    except Exception as exc:  # noqa: BLE001 — broad on purpose (D-118)
        logger.error("otel: tracer init failed, tracing disabled: %s", exc, exc_info=True)
        return None


def shutdown_telemetry() -> None:
    """Flush pending spans and shut the TracerProvider down.

    Register this on app shutdown so in-flight spans are not dropped.
    No-op when tracing was never initialized or failed to initialize.
    """
    global _tracer_provider
    if _tracer_provider is None:
        return
    try:
        _tracer_provider.shutdown()
    except Exception as exc:  # noqa: BLE001
        logger.error("otel: tracer shutdown error: %s", exc, exc_info=True)
    finally:
        _tracer_provider = None


__all__ = ["setup_telemetry", "shutdown_telemetry"]
