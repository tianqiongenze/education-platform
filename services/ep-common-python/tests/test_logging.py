"""Tests for ep_common.logging (D-110)."""

from __future__ import annotations

import io
import json
import logging

import pytest

from ep_common.logging import JsonFormatter, setup_logging


def _emit(service: str = "svc", extra: dict | None = None) -> dict:
    """Run setup_logging against an in-memory stream, emit one record, return parsed JSON."""
    stream = io.StringIO()
    setup_logging(service_name=service, level="DEBUG", stream=stream, force=True)
    logger = logging.getLogger("ep.test")
    logger.info("hello %s", "world", extra=extra or {})
    line = stream.getvalue().strip()
    return json.loads(line)


def test_json_formatter_emits_required_fields():
    record = _emit(service="ai-platform")
    assert record["message"] == "hello world"
    assert record["level"] == "INFO"
    assert record["logger"] == "ep.test"
    assert record["service"] == "ai-platform"
    assert "timestamp" in record


def test_json_formatter_includes_extras():
    record = _emit(extra={"request_id": "req-1", "user_id": 42})
    assert record["request_id"] == "req-1"
    assert record["user_id"] == 42


def test_json_formatter_includes_exception():
    stream = io.StringIO()
    setup_logging(stream=stream, force=True)
    logger = logging.getLogger("ep.exc")
    try:
        raise ValueError("boom")
    except ValueError:
        logger.exception("failed")
    record = json.loads(stream.getvalue().strip().splitlines()[-1])
    assert record["message"] == "failed"
    assert "ValueError: boom" in record["exception"]


def test_setup_logging_sets_level():
    stream = io.StringIO()
    root = setup_logging(level="WARNING", stream=stream, force=True)
    assert root.level == logging.WARNING


def test_setup_logging_force_replaces_handlers():
    stream_a = io.StringIO()
    setup_logging(stream=stream_a, force=True)
    root = logging.getLogger()
    n_after_first = len(root.handlers)
    stream_b = io.StringIO()
    setup_logging(stream=stream_b, force=True)
    # force=True should not accumulate handlers across calls.
    assert len(root.handlers) == n_after_first


def test_formatter_directly():
    fmt = JsonFormatter(service_name="direct")
    record = logging.LogRecord(
        name="x", level=logging.INFO, pathname=__file__, lineno=1,
        msg="plain", args=(), exc_info=None,
    )
    parsed = json.loads(fmt.format(record))
    assert parsed["service"] == "direct"
    assert parsed["message"] == "plain"


@pytest.fixture(autouse=True)
def _reset_root_after_test():
    yield
    # Reset root logger so test cross-talk cannot leak into other suites.
    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
    root.setLevel(logging.WARNING)
