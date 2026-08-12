"""D-110: Unified JSON response envelopes for education-platform services.

Mirrors the Go :mod:`ep-common-go/httpresp` shape so success and error
bodies are consistent across all platform services regardless of language.

Success::

    {"code": 200, "message": "OK", "data": ...}

Error::

    {"code": "BAD_REQUEST", "message": "...", "status": 400, "traceId": "..."}
"""

from __future__ import annotations

from typing import Any, Optional


def success(data: Any = None, message: str = "OK", code: int = 200) -> dict[str, Any]:
    """Build a success envelope.

    ``data`` is omitted from the result when ``None`` to keep payloads small,
    matching the Go service behavior (``omitempty``).
    """
    payload: dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        payload["data"] = data
    return payload


def error(
    code: str,
    message: str,
    status: int = 400,
    trace_id: Optional[str] = None,
) -> dict[str, Any]:
    """Build an error envelope.

    Parameters
    ----------
    code:
        Machine-readable error code, e.g. ``"BAD_REQUEST"``.
    message:
        Human-readable error description.
    status:
        HTTP status code to surface to the caller.
    trace_id:
        Optional correlation id; omitted from the output when ``None``.
    """
    payload: dict[str, Any] = {
        "code": code,
        "message": message,
        "status": status,
    }
    if trace_id:
        payload["traceId"] = trace_id
    return payload
