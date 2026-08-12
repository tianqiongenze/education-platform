"""PluginRegistry — per-tenant catalog of installed plugins.

Mirrors the caching strategy of :class:`ai_mcp.registry.MCPServerRegistry`:
Redis first (key ``plugin:registry:{tenant}``), local YAML fallback, and an
in-memory mirror for tests.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

from ai_plugin.manifest import PluginManifest

logger = logging.getLogger(__name__)

# D-033 iron-rule key.
REDIS_KEY_TEMPLATE = "plugin:registry:{tenant}"

DEFAULT_YAML_PATH = Path(os.environ.get("EP_PLUGIN_REGISTRY_YAML", "config/plugin_registry.yaml"))


class PluginRegistry:
    """Tenant-scoped registry of plugin manifests."""

    def __init__(self, redis: Any | None = None, yaml_path: Path | str | None = None) -> None:
        self._redis = redis
        self._yaml_path = Path(yaml_path) if yaml_path else DEFAULT_YAML_PATH
        self._memory: dict[str, dict[str, PluginManifest]] = {}

    # ------------------------------------------------------------- public

    async def register(self, manifest: PluginManifest) -> None:
        """Register a plugin for every tenant in its whitelist."""
        tenants = manifest.tenant_whitelist if manifest.tenant_whitelist else ["*"]
        for tenant_id in tenants:
            self._memory.setdefault(tenant_id, {})[manifest.plugin_id] = manifest
            await self._persist(tenant_id)
        logger.info("Registered plugin %s for tenants %s", manifest.plugin_id, tenants)

    async def unregister(self, plugin_id: str, tenant_id: str | None = None) -> None:
        """Remove a plugin from one tenant (or all tenants if None)."""
        tenants = [tenant_id] if tenant_id else list(self._memory.keys())
        for tid in tenants:
            bucket = self._memory.get(tid, {})
            if plugin_id in bucket:
                del bucket[plugin_id]
                await self._persist(tid)

    async def list_plugins(self, tenant_id: str) -> list[PluginManifest]:
        """List plugins available to ``tenant_id`` (tenant-specific + global)."""
        await self._load(tenant_id)
        merged: dict[str, PluginManifest] = {}
        merged.update(self._memory.get("*", {}))
        merged.update(self._memory.get(tenant_id, {}))
        # Filter by whitelist (in case of stale global entries).
        return [p for p in merged.values() if p.is_allowed_for_tenant(tenant_id)]

    async def get_plugin(self, plugin_id: str, tenant_id: str = "*") -> PluginManifest | None:
        await self._load(tenant_id)
        return self._memory.get(tenant_id, {}).get(plugin_id) or self._memory.get("*", {}).get(plugin_id)

    # ---------------------------------------------------------- persistence

    async def _persist(self, tenant_id: str) -> None:
        payload = json.dumps([p.to_dict() for p in self._memory.get(tenant_id, {}).values()])
        if self._redis is not None:
            try:
                await self._redis.set(REDIS_KEY_TEMPLATE.format(tenant=tenant_id), payload)
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning("Redis persist failed for tenant %s: %s", tenant_id, exc)
        self._write_yaml()

    async def _load(self, tenant_id: str) -> None:
        if tenant_id in self._memory:
            return
        if self._redis is not None:
            try:
                raw = await self._redis.get(REDIS_KEY_TEMPLATE.format(tenant=tenant_id))
                if raw:
                    data = json.loads(raw)
                    self._memory[tenant_id] = {p["plugin_id"]: PluginManifest.from_dict(p) for p in data}
                    return
            except Exception as exc:  # noqa: BLE001
                logger.warning("Redis load failed for tenant %s: %s", tenant_id, exc)
        self._read_yaml()

    # ----------------------------------------------------------------- yaml

    def _read_yaml(self) -> None:
        if not self._yaml_path.exists():
            return
        try:
            import yaml
        except ImportError:  # pragma: no cover
            return
        with self._yaml_path.open("r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}
        for tenant_id, plugins in (data.get("tenants", {}) or {}).items():
            bucket: dict[str, PluginManifest] = {}
            for p in plugins:
                m = PluginManifest.from_dict(p)
                bucket[m.plugin_id] = m
            self._memory.setdefault(tenant_id, {}).update(bucket)

    def _write_yaml(self) -> None:
        try:
            import yaml
        except ImportError:  # pragma: no cover
            return
        self._yaml_path.parent.mkdir(parents=True, exist_ok=True)
        data = {"tenants": {tid: [p.to_dict() for p in bucket.values()] for tid, bucket in self._memory.items()}}
        with self._yaml_path.open("w", encoding="utf-8") as fh:
            yaml.safe_dump(data, fh, default_flow_style=False)
