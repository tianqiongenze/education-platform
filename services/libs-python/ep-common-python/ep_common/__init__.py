"""Education-platform shared Python utilities (D-110).

Public submodules:

- :mod:`ep_common.config`   — environment-variable loaders.
- :mod:`ep_common.health`   — health-check response factory.
- :mod:`ep_common.response` — unified success/error JSON envelopes.
- :mod:`ep_common.logging`  — structured JSON logging.
- :mod:`ep_common.cache`    — unified L1+L2 cache with Pub/Sub broadcast.

The package deliberately has zero third-party runtime dependencies so it can
be reused by any service (Litestar/Starlette/FastAPI) without pulling in a
web framework. The cache module imports ``redis`` lazily so it remains
optional.
"""

from __future__ import annotations

__version__ = "0.1.0"

__all__ = ["__version__"]


def __getattr__(name: str):  # PEP 562 — lazy module attribute access
    if name == "CacheService":
        from .cache import CacheService

        return CacheService
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
