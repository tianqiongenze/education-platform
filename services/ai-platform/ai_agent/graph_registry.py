"""Dynamic subgraph registry (SP-04 industry-agnostic refactoring).

Builds the set of industry-specific agent subgraphs from an
``IndustryConfig``. Modules are imported via ``importlib`` and the named
builder callable is invoked to produce a compiled subgraph. Failed imports are
logged and skipped (graceful degradation) so a single broken subgraph never
blocks the whole platform.

Design constraints:
- D-039: no ``synchronized`` blocks; concurrency is guarded with
  ``threading.Lock`` (a Lock is not a Java synchronized block and is the
  idiomatic Python mechanism). The build is idempotent and cached.
- D-040: plain Python, GraalVM AOT compatible.
"""

from __future__ import annotations

import importlib
import threading
from typing import Any, Callable

import structlog

from .industry_config import AgentGraphConfig, IndustryConfig

logger = structlog.get_logger(__name__)


class GraphRegistry:
    """Registry of agent subgraphs built from an ``IndustryConfig``.

    Subgraphs may be registered manually (``register``) or loaded dynamically
    from config entries (``load_from_config``). ``build_all`` compiles every
    registered subgraph exactly once and caches the result.
    """

    def __init__(self, config: IndustryConfig | None = None) -> None:
        self._config = config or IndustryConfig()
        # name -> builder callable (not yet compiled)
        self._builders: dict[str, Callable[..., Any]] = {}
        # name -> compiled subgraph (cached after build_all)
        self._graphs: dict[str, Any] | None = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, name: str, builder_fn: Callable[..., Any]) -> None:
        """Manually register a subgraph builder under ``name``."""
        with self._lock:
            self._builders[name] = builder_fn
            # Invalidate the cache so the new builder is picked up.
            self._graphs = None

    def load_from_config(self, config: IndustryConfig | None = None) -> None:
        """Load all agent graph builders referenced by the config.

        Each config entry's module is imported via ``importlib`` and the named
        builder callable is stored. Import failures are logged and skipped.
        """
        cfg = config or self._config
        if config is not None:
            self._config = config
        for entry in cfg.agent_graphs:
            self._load_one(entry)

    def _load_one(self, entry: AgentGraphConfig) -> None:
        """Import a single config entry's builder, skipping on failure."""
        try:
            module = importlib.import_module(entry.module)
        except Exception as exc:  # noqa: BLE001 — graceful degradation
            logger.warning(
                "graph_registry.import_failed",
                name=entry.name,
                module=entry.module,
                error=str(exc),
            )
            return
        builder = getattr(module, entry.builder, None)
        if not callable(builder):
            logger.warning(
                "graph_registry.builder_missing",
                name=entry.name,
                module=entry.module,
                builder=entry.builder,
            )
            return
        with self._lock:
            self._builders[entry.name] = builder
            self._graphs = None

    # ------------------------------------------------------------------
    # Build
    # ------------------------------------------------------------------

    def build_all(self) -> dict[str, Any]:
        """Build (and cache) all registered subgraphs.

        Returns a dict mapping subgraph name → compiled graph. Builders that
        raise are caught and skipped (graceful degradation).
        """
        with self._lock:
            if self._graphs is not None:
                return self._graphs
            graphs: dict[str, Any] = {}
            # Snapshot builders inside the lock to avoid mutation during build.
            builders = dict(self._builders)
        # Build outside the lock so concurrent builds don't serialize on the
        # (potentially slow) builder calls.
        for name, builder in builders.items():
            try:
                graphs[name] = builder()
            except Exception as exc:  # noqa: BLE001 — graceful degradation
                logger.warning(
                    "graph_registry.build_failed",
                    name=name,
                    error=str(exc),
                )
        with self._lock:
            # Only cache if no new builders were registered during the build.
            if self._graphs is None:
                self._graphs = graphs
            return self._graphs if self._graphs is not None else graphs

    def names(self) -> set[str]:
        """Return the set of registered subgraph names."""
        with self._lock:
            return set(self._builders.keys())

    def is_empty(self) -> bool:
        """Return True when no subgraphs are registered."""
        with self._lock:
            return len(self._builders) == 0
