"""Tests for ep_common.response (D-110)."""

from __future__ import annotations

from ep_common.response import error, success


def test_success_with_data():
    result = success({"id": 1})
    assert result == {"code": 200, "message": "OK", "data": {"id": 1}}


def test_success_omits_none_data():
    result = success()
    assert result == {"code": 200, "message": "OK"}
    assert "data" not in result


def test_success_custom_message_and_code():
    result = success(data={"id": 2}, message="created", code=201)
    assert result["code"] == 201
    assert result["message"] == "created"


def test_error_basic():
    result = error("BAD_REQUEST", "missing field")
    assert result == {
        "code": "BAD_REQUEST",
        "message": "missing field",
        "status": 400,
    }
    assert "traceId" not in result


def test_error_with_trace_id():
    result = error("INTERNAL", "boom", status=500, trace_id="abc-123")
    assert result["status"] == 500
    assert result["traceId"] == "abc-123"


def test_error_empty_trace_id_omitted():
    result = error("X", "y", trace_id="")
    assert "traceId" not in result
