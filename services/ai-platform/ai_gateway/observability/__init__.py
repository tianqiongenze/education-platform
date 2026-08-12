"""Observability package for ai-platform (D-118 OTel instrumentation)."""

from .otel import setup_telemetry, shutdown_telemetry

__all__ = ["setup_telemetry", "shutdown_telemetry"]
