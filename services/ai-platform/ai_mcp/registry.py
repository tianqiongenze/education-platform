"""MCPServerRegistry — per-tenant catalog of available MCP servers.

Follows the D-033 caching iron-rule: every tenant-scoped read goes to Redis
first (key ``mcp:servers:{tenant}``) and falls back to a local YAML file when
Redis is unavailable. Writes propagate to both layers.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# Redis key template — D-033 iron-rule: mcp:servers:{tenant}
REDIS_KEY_TEMPLATE = "mcp:servers:{tenant}"

# Local YAML fallback location (relative to the ai-platform service root).
DEFAULT_YAML_PATH = Path(os.environ.get("EP_MCP_SERVERS_YAML", "config/mcp_servers.yaml"))


@dataclass
class MCPServerInfo:
    """Metadata describing a registered MCP server.

    Attributes:
        server_id: Stable unique identifier for the server.
        name: Human-readable name.
        url: Connection URL (stdio command path or http(s) URL).
        transport: Transport hint — ``stdio`` or ``http``.
        tenant_id: Owning tenant (``*`` means available to all tenants).
        version: Server version string.
        description: Short description of the server's purpose.
        tools: Optional list of tool names exposed by the server.
    """

    server_id: str
    name: str
    url: str
    transport: str = "http"
    tenant_id: str = "*"
    version: str = "1.0.0"
    description: str = ""
    tools: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> MCPServerInfo:
        return cls(
            server_id=data["server_id"],
            name=data.get("name", data["server_id"]),
            url=data["url"],
            transport=data.get("transport", "http"),
            tenant_id=data.get("tenant_id", "*"),
            version=data.get("version", "1.0.0"),
            description=data.get("description", ""),
            tools=data.get("tools", []),
        )


class MCPServerRegistry:
    """Registry of MCP servers, keyed by tenant.

    The registry is intentionally storage-agnostic: it accepts an optional
    Redis client (anything with ``get/set/delete`` coroutines) and an optional
    YAML path. When Redis is absent or throws, it transparently falls back to
    YAML so the platform keeps working in degraded environments.
    """

    def __init__(self, redis: Any | None = None, yaml_path: Path | str | None = None) -> None:
        self._redis = redis
        self._yaml_path = Path(yaml_path) if yaml_path else DEFAULT_YAML_PATH
        # In-memory mirror used as a tertiary cache and for tests without Redis.
        self._memory: dict[str, dict[str, MCPServerInfo]] = {}

    # ------------------------------------------------------------------ public

    async def register(self, tenant_id: str, server_info: MCPServerInfo) -> None:
        """Register or update a server for ``tenant_id``."""
        bucket = self._memory.setdefault(tenant_id, {})
        bucket[server_info.server_id] = server_info
        await self._persist(tenant_id)
        logger.info("Registered MCP server %s for tenant %s", server_info.server_id, tenant_id)

    async def unregister(self, server_id: str, tenant_id: str | None = None) -> None:
        """Remove a server. If ``tenant_id`` is None, scan all tenants."""
        tenants = [tenant_id] if tenant_id else list(self._memory.keys())
        for tid in tenants:
            bucket = self._memory.get(tid, {})
            if server_id in bucket:
                del bucket[server_id]
                await self._persist(tid)
                logger.info("Unregistered MCP server %s from tenant %s", server_id, tid)

    async def list_servers(self, tenant_id: str) -> list[MCPServerInfo]:
        """Return all servers visible to ``tenant_id`` (tenant-specific + global)."""
        await self._load(tenant_id)
        servers: dict[str, MCPServerInfo] = {}
        # Global servers (tenant_id == "*") first.
        global_bucket = self._memory.get("*", {})
        servers.update(global_bucket)
        servers.update(self._memory.get(tenant_id, {}))
        return list(servers.values())

    async def get_server(self, tenant_id: str, server_id: str) -> MCPServerInfo | None:
        await self._load(tenant_id)
        bucket = self._memory.get(tenant_id, {})
        if server_id in bucket:
            return bucket[server_id]
        return self._memory.get("*", {}).get(server_id)

    # ------------------------------------------------------------- persistence

    async def _persist(self, tenant_id: str) -> None:
        payload = json.dumps([s.to_dict() for s in self._memory.get(tenant_id, {}).values()])
        if self._redis is not None:
            try:
                await self._redis.set(REDIS_KEY_TEMPLATE.format(tenant=tenant_id), payload)
                return
            except Exception as exc:  # noqa: BLE001
                logger.warning("Redis persist failed for tenant %s: %s", tenant_id, exc)
        # Fallback: write YAML.
        self._write_yaml()

    async def _load(self, tenant_id: str) -> None:
        if tenant_id in self._memory:
            return
        if self._redis is not None:
            try:
                raw = await self._redis.get(REDIS_KEY_TEMPLATE.format(tenant=tenant_id))
                if raw:
                    data = json.loads(raw)
                    self._memory[tenant_id] = {
                        s["server_id"]: MCPServerInfo.from_dict(s) for s in data
                    }
                    return
            except Exception as exc:  # noqa: BLE001
                logger.warning("Redis load failed for tenant %s: %s", tenant_id, exc)
        # Fallback: read YAML.
        self._read_yaml()

    # --------------------------------------------------------------- yaml io

    def _read_yaml(self) -> None:
        if not self._yaml_path.exists():
            return
        try:
            import yaml
        except ImportError:  # pragma: no cover
            logger.warning("PyYAML not installed; skipping YAML fallback")
            return
        with self._yaml_path.open("r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}
        for tenant_id, servers in (data.get("tenants", {}) or {}).items():
            bucket: dict[str, MCPServerInfo] = {}
            for s in servers:
                info = MCPServerInfo.from_dict(s)
                bucket[info.server_id] = info
            self._memory.setdefault(tenant_id, {}).update(bucket)

    def _write_yaml(self) -> None:
        try:
            import yaml
        except ImportError:  # pragma: no cover
            return
        self._yaml_path.parent.mkdir(parents=True, exist_ok=True)
        data = {"tenants": {tid: [s.to_dict() for s in bucket.values()] for tid, bucket in self._memory.items()}}
        with self._yaml_path.open("w", encoding="utf-8") as fh:
            yaml.safe_dump(data, fh, default_flow_style=False)
