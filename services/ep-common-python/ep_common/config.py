"""D-110: Unified environment configuration loader.

These helpers wrap :mod:`os.environ` with sensible defaults and validation.
They are intentionally framework-agnostic so any Python service in the
education platform can reuse them. No third-party dependencies.
"""

from __future__ import annotations

import os
from typing import Optional


class MissingEnvironmentVariableError(RuntimeError):
    """Raised when a required environment variable is not set."""


_TRUE_VALUES = frozenset({"1", "true", "yes", "on", "y", "t"})
_FALSE_VALUES = frozenset({"0", "false", "no", "off", "n", "f"})


def get_env(key: str, default: Optional[str] = None, required: bool = False) -> Optional[str]:
    """Return the environment variable ``key``.

    Resolution order:

    1. If ``key`` is present in the environment, return its value (stripped of
       surrounding whitespace for robustness against common copy/paste mistakes).
    2. Otherwise return ``default``.
    3. If ``required`` is ``True`` and the resolved value is falsy (empty or
       ``None``), raise :class:`MissingEnvironmentVariableError`.
    """
    raw = os.environ.get(key)
    value = raw.strip() if raw is not None else raw
    resolved = value if value not in (None, "") else default
    if required and not resolved:
        raise MissingEnvironmentVariableError(
            f"Required environment variable {key!r} is not set"
        )
    return resolved


def get_int(key: str, default: int = 0) -> int:
    """Return ``key`` parsed as an ``int``, falling back to ``default``.

    A missing or empty variable yields ``default``. A malformed value also
    yields ``default`` rather than raising, matching ai-platform's existing
    ``int(_get(...) or <default>)`` pattern.
    """
    value = os.environ.get(key)
    if value is None or value.strip() == "":
        return default
    try:
        return int(value.strip())
    except ValueError:
        return default


def get_bool(key: str, default: bool = False) -> bool:
    """Return ``key`` parsed as a bool.

    Truthy strings: ``1, true, yes, on, y, t`` (case-insensitive).
    Falsy strings: ``0, false, no, off, n, f`` (case-insensitive).
    Missing/empty/unrecognized values yield ``default``.
    """
    value = os.environ.get(key)
    if value is None or value.strip() == "":
        return default
    norm = value.strip().lower()
    if norm in _TRUE_VALUES:
        return True
    if norm in _FALSE_VALUES:
        return False
    return default


def get_list(
    key: str,
    default: Optional[list[str]] = None,
    sep: str = ",",
) -> list[str]:
    """Return ``key`` split on ``sep`` into a list of stripped non-empty items.

    Missing/empty variable yields ``default`` (or ``[]`` when ``default`` is
    ``None``). Empty items between separators are dropped.
    """
    if default is None:
        default = []
    value = os.environ.get(key)
    if value is None or value.strip() == "":
        return list(default)
    return [item.strip() for item in value.split(sep) if item.strip()]
