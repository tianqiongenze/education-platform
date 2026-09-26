"""D-110: Structured JSON logging setup.

Provides a :class:`JsonFormatter` that renders log records as one JSON object
per line (suitable for Loki / ELK ingestion) plus a :func:`setup_logging`
helper to wire it into the root logger.

No third-party dependencies. Mirrors the structured-logging intent ai-platform
expresses via ``structlog`` while remaining self-contained.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone
from typing import Any

# Canonical attributes of logging.LogRecord. Anything in vars(record) that is
# NOT in this set was supplied by the caller via ``extra={...}`` and should be
# surfaced into the JSON payload.
_STANDARD_RECORD_ATTRS = frozenset({
    "name", "msg", "args", "levelname", "levelno", "pathname", "filename",
    "module", "exc_info", "exc_text", "stack_info", "lineno", "funcName",
    "created", "msecs", "relativeCreated", "thread", "threadName",
    "processName", "process", "taskName", "getMessage",
})


class JsonFormatter(logging.Formatter):
    """Render a :class:`logging.LogRecord` as a single-line JSON object.

    Fields: ``timestamp``, ``level``, ``logger``, ``message``, ``service``,
    plus any ``extra`` attributes attached to the record and, on exceptions,
    a stringified ``exception``.
    """

    def __init__(self, service_name: str = "service", *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._service_name = service_name

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "service": self._service_name,
            "message": record.getMessage(),
        }

        # Attach non-standard record attributes (set via logger.info(..., extra=...)).
        # Anything not in the canonical LogRecord attribute set is treated as a
        # user-supplied extra and surfaced into the JSON payload.
        for key, value in vars(record).items():
            if key in _STANDARD_RECORD_ATTRS or key.startswith("_"):
                continue
            payload[key] = value

        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)

        return json.dumps(payload, default=str, ensure_ascii=False)


def setup_logging(
    service_name: str = "service",
    level: str = "INFO",
    *,
    stream: Any = None,
    force: bool = True,
) -> logging.Logger:
    """Configure the root logger to emit JSON lines.

    Parameters
    ----------
    service_name:
        Value written to the ``service`` field of every record.
    level:
        Logging level name (e.g. ``"INFO"``, ``"DEBUG"``).
    stream:
        Output stream; defaults to :data:`sys.stdout`.
    force:
        Passed to :func:`logging.basicConfig` to remove any pre-existing
        handlers on the root logger (default ``True`` so re-invocations in
        tests are predictable).
    """
    numeric_level = _parse_level(level)
    handler = logging.StreamHandler(stream or sys.stdout)
    handler.setFormatter(JsonFormatter(service_name=service_name))
    root = logging.getLogger()
    if force:
        for h in list(root.handlers):
            root.removeHandler(h)
    root.addHandler(handler)
    root.setLevel(numeric_level)
    return root


def _parse_level(level: str) -> int:
    """Resolve a level name (case-insensitive) to its numeric value."""
    if isinstance(level, int):
        return level
    normalized = str(level).strip().upper()
    return logging.getLevelName(normalized) if normalized else logging.INFO
