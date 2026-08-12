"""Education-platform shared Python utilities (D-110).

Public submodules:

- :mod:`ep_common.config`   — environment-variable loaders.
- :mod:`ep_common.health`   — health-check response factory.
- :mod:`ep_common.response` — unified success/error JSON envelopes.
- :mod:`ep_common.logging`  — structured JSON logging.

The package deliberately has zero third-party runtime dependencies so it can
be reused by any service (Litestar/Starlette/FastAPI) without pulling in a
web framework.
"""

from __future__ import annotations

__version__ = "0.1.0"

__all__ = ["__version__"]
