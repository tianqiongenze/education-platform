"""PluginSandbox — process-level isolation and permission enforcement.

Each plugin runs in its own stdio child process (the MCP server). If the
plugin crashes, the host agent is unaffected. The sandbox additionally
enforces per-tenant permissions and a hard timeout on every tool call.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from ai_plugin.manifest import PluginManifest, PluginPermission
from ai_plugin.registry import PluginRegistry

logger = logging.getLogger(__name__)

# D-046: single tool call must not exceed 30s.
DEFAULT_TOOL_TIMEOUT = 30.0


class PluginSandbox:
    """Enforces isolation, permissions, and timeouts for plugin tool calls.

    The sandbox is policy-only: it does not spawn processes itself (the
    :class:`~ai_mcp.transport.StdioTransport` does that), but it gates every
    tool invocation behind a permission check and a timeout wrapper so a
    misbehaving plugin cannot hang or escalate.
    """

    def __init__(
        self,
        registry: PluginRegistry,
        *,
        tool_timeout: float = DEFAULT_TOOL_TIMEOUT,
    ) -> None:
        self._registry = registry
        self.tool_timeout = tool_timeout

    async def check_permission(
        self,
        plugin_id: str,
        tenant_id: str,
        permission: PluginPermission | str,
    ) -> bool:
        """Return True if ``plugin_id`` may exercise ``permission`` for ``tenant_id``.

        The plugin must (a) be registered for the tenant, (b) pass the tenant
        whitelist, and (c) declare the requested permission in its manifest.
        """
        manifest = await self._registry.get_plugin(plugin_id, tenant_id)
        if manifest is None:
            logger.warning("Permission denied: plugin %s not found for tenant %s", plugin_id, tenant_id)
            return False
        if not manifest.is_allowed_for_tenant(tenant_id):
            logger.warning("Permission denied: plugin %s not whitelisted for tenant %s", plugin_id, tenant_id)
            return False
        perm_value = permission.value if isinstance(permission, PluginPermission) else str(permission)
        granted = any(p.value == perm_value for p in manifest.required_permissions)
        if not granted:
            logger.warning(
                "Permission denied: plugin %s does not declare permission %s", plugin_id, perm_value
            )
        return granted

    async def enforce_timeout(self, coro: Any, *, timeout: float | None = None) -> Any:
        """Run ``coro`` with a hard timeout (default :attr:`tool_timeout`).

        Raises :class:`TimeoutError` if the coroutine does not complete in time.
        """
        return await asyncio.wait_for(coro, timeout=timeout or self.tool_timeout)

    async def guard(
        self,
        plugin_id: str,
        tenant_id: str,
        permission: PluginPermission | str,
        coro: Any,
        *,
        timeout: float | None = None,
    ) -> Any:
        """Convenience wrapper: check permission then run with timeout."""
        if not await self.check_permission(plugin_id, tenant_id, permission):
            raise PermissionError(
                f"Plugin {plugin_id!r} lacks permission {permission!r} for tenant {tenant_id!r}"
            )
        return await self.enforce_timeout(coro, timeout=timeout)
