"""Tool discovery via vector similarity over tool descriptions.

When a tenant has many plugins (hence many tools), injecting every tool into
the LLM context is wasteful and risks overflowing it. This module retrieves
the top-k tools whose descriptions are most similar to a natural-language
query, keeping the context lean.
"""

from __future__ import annotations

import logging
import math
from typing import Any

from ai_mcp.client import MCPClient
from ai_mcp.schema import ToolSchema
from ai_plugin.registry import PluginRegistry

logger = logging.getLogger(__name__)


class PluginToolDiscovery:
    """Retrieve the top-k most relevant tools for a query.

    Uses a pluggable embedder (anything with ``embed(text) -> list[float]``)
    and a simple cosine-similarity ranker. When no embedder is supplied, a
    lightweight keyword-overlap scorer is used so the module is usable in
    test environments without a model dependency.
    """

    def __init__(
        self,
        registry: PluginRegistry,
        client: MCPClient | None = None,
        embedder: Any | None = None,
    ) -> None:
        self._registry = registry
        self._client = client or MCPClient()
        self._embedder = embedder
        # Cache: (tenant_id, tool_name) -> embedding vector
        self._embeddings: dict[tuple[str, str], list[float]] = {}

    async def discover_tools(
        self,
        query: str,
        tenant_id: str,
        top_k: int = 10,
    ) -> list[ToolSchema]:
        """Return the ``top_k`` tools most relevant to ``query`` for ``tenant_id``."""
        # Gather all tools across the tenant's plugins.
        plugins = await self._registry.list_plugins(tenant_id)
        all_tools: list[tuple[ToolSchema, str]] = []  # (schema, server_url)
        for plugin in plugins:
            try:
                schemas = await self._client.list_tools(plugin.mcp_server_url)
            except Exception as exc:  # noqa: BLE001
                logger.warning("Failed to list tools for plugin %s: %s", plugin.plugin_id, exc)
                continue
            for s in schemas:
                all_tools.append((s, plugin.mcp_server_url))

        if not all_tools:
            return []

        if self._embedder is not None:
            scored = self._rank_by_embedding(query, tenant_id, all_tools)
        else:
            scored = self._rank_by_keyword(query, all_tools)

        scored.sort(key=lambda x: x[0], reverse=True)
        return [s for _, s in scored[:top_k]]

    # ----------------------------------------------------- embedding rank

    def _rank_by_embedding(
        self,
        query: str,
        tenant_id: str,
        tools: list[tuple[ToolSchema, str]],
    ) -> list[tuple[float, ToolSchema]]:
        query_vec = self._embed(query)
        results: list[tuple[float, ToolSchema]] = []
        for schema, _server in tools:
            key = (tenant_id, schema.name)
            if key not in self._embeddings:
                self._embeddings[key] = self._embed(f"{schema.name}: {schema.description}")
            sim = _cosine(query_vec, self._embeddings[key])
            results.append((sim, schema))
        return results

    def _embed(self, text: str) -> list[float]:
        vec = self._embedder.embed(text) if hasattr(self._embedder, "embed") else self._embedder(text)
        return list(vec)

    # ------------------------------------------------------ keyword rank

    @staticmethod
    def _rank_by_keyword(
        query: str,
        tools: list[tuple[ToolSchema, str]],
    ) -> list[tuple[float, ToolSchema]]:
        q_tokens = {t.lower() for t in query.split() if t.strip()}
        results: list[tuple[float, ToolSchema]] = []
        for schema, _server in tools:
            desc_tokens = {t.lower() for t in (schema.name + " " + schema.description).split() if t.strip()}
            if not q_tokens:
                results.append((0.0, schema))
                continue
            overlap = len(q_tokens & desc_tokens)
            score = overlap / math.sqrt(len(q_tokens) * max(len(desc_tokens), 1))
            results.append((score, schema))
        return results


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)
