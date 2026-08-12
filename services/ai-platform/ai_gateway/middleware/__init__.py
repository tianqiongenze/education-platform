"""ai_gateway.middleware package — D-139 P1-5 JWT auth.

This package coexists with the sibling ``middleware.py`` module. Python
resolves ``from .middleware import X`` to this package (directory) first.
However, ``middleware.py`` (the sibling module) is shadowed by this
package — importing it via ``from .middleware import ...`` will always hit
the package, never the module.

To avoid a circular import (the package importing the module that
importers expect to BE the package), app.py and tests should import
``TraceContextASGIMiddleware``, ``logging_middleware_config``, and
``rate_limit_config`` directly from ``ai_gateway.middleware.middleware``
(the module), and JWT auth from ``ai_gateway.middleware.jwt_auth``.

This __init__ only exports JWT auth to keep the package lightweight.
"""

from __future__ import annotations

from .jwt_auth import JWTAuthMiddleware, PUBLIC_PATHS, get_jwt_backend

__all__ = ["JWTAuthMiddleware", "PUBLIC_PATHS", "get_jwt_backend"]
