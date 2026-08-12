"""Tests for ep_common.health (D-110)."""

from __future__ import annotations

from ep_common.health import health_response, health_response_down


def test_health_response_up_shape():
    result = health_response("ai-platform", "1.2.3")
    assert result == {
        "status": "UP",
        "service": "ai-platform",
        "version": "1.2.3",
    }


def test_health_response_defaults_version():
    result = health_response("svc")
    assert result["status"] == "UP"
    assert result["version"] == "1.0.0"


def test_health_response_down_without_reason():
    result = health_response_down("svc")
    assert result["status"] == "DOWN"
    assert "reason" not in result


def test_health_response_down_with_reason():
    result = health_response_down("svc", reason="db unreachable")
    assert result["status"] == "DOWN"
    assert result["reason"] == "db unreachable"
