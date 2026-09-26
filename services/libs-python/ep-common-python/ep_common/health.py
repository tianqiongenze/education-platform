"""D-110: Health-check response factory.

Framework-agnostic: returns a plain dict matching the platform's standard
health shape. Works with Litestar, Starlette, FastAPI, etc.
"""

from __future__ import annotations

from typing import Any


def health_response(service_name: str, version: str = "1.0.0") -> dict[str, Any]:
    """Return the standard health-check response dict.

    Example::

        {"status": "UP", "service": "ai-platform", "version": "1.0.0"}
    """
    return {
        "status": "UP",
        "service": service_name,
        "version": version,
    }


def health_response_down(service_name: str, reason: str = "", version: str = "1.0.0") -> dict[str, Any]:
    """Return a DOWN health response, optionally carrying a reason string."""
    payload: dict[str, Any] = {
        "status": "DOWN",
        "service": service_name,
        "version": version,
    }
    if reason:
        payload["reason"] = reason
    return payload
