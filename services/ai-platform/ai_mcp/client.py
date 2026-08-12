"""MCPClient — discover and call MCP-server-hosted tools.

The client delegates all JSON-RPC work to the **official MCP Python SDK**
(``mcp.ClientSession``) via the :class:`~ai_mcp.transport.Transport` wrappers
in :mod:`ai_mcp.transport`. A single :class:`MCPClient` instance can talk to
many servers, caching their tool schemas to avoid repeated discovery
round-trips.

No protocol framing is hand-written: ``list_tools`` / ``call_tool`` flow
through ``ClientSession.list_tools`` / ``ClientSession.call_tool``.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from ai_mcp.registry import MCPServerInfo, MCPServerRegistry
from ai_mcp.schema import ToolSchema, sanitize_tool_description, validate_tool_args
from ai_mcp.transport import HTTPTransport, StdioTransport, Transport, select_transport

logger = logging.getLogger(__name__)


class MCPClient:
    """Stateful client for one or more MCP servers.

    Example::

        client = MCPClient(registry=registry)
        tools = await client.list_tools("https://knowledge-bff/mcp", tenant_id="t1")
        result = await client.call_tool("https://knowledge-bff/mcp", "search_kb",
                                        {"query": "fractions", "tenantId": "t1"})
    """

    def __init__(self, registry: MCPServerRegistry | None = None) -> None:
        self._registry = registry or MCPServerRegistry()
        # Cache of (server_url -> Transport) so we reuse connections.
        self._transports: dict[str, Transport] = {}
        # Cache of (server_url -> list[ToolSchema]) to avoid repeated discovery.
        self._tool_cache: dict[str, list[ToolSchema]] = {}

    # ------------------------------------------------------------- transport

    async def _get_transport(self, server_url: str) -> Transport:
        if server_url in self._transports and self._transports[server_url].is_connected:
            return self._transports[server_url]
        transport = select_transport(server_url)
        await transport.connect()
        self._transports[server_url] = transport
        return transport

    async def close(self) -> None:
        """Close all cached transports."""
        for transport in list(self._transports.values()):
            try:
                await transport.close()
            except Exception as exc:  # noqa: BLE001
                logger.warning("Error closing transport: %s", exc)
        self._transports.clear()
        self._tool_cache.clear()

    # ----------------------------------------------------------------- tools

    async def list_tools(self, server_url: str, *, use_cache: bool = True) -> list[ToolSchema]:
        """List the tools exposed by an MCP server.

        Args:
            server_url: Connection URL (http(s):// or stdio command path).
            use_cache: If True, return cached schemas when available.

        Returns:
            A list of :class:`ToolSchema` describing each tool.
        """
        if use_cache and server_url in self._tool_cache:
            return self._tool_cache[server_url]

        transport = await self._get_transport(server_url)
        response = await transport.request("tools/list", {})
        tools = _parse_tools_response(response)
        if use_cache:
            self._tool_cache[server_url] = tools
        return tools

    async def call_tool(
        self,
        server_url: str,
        tool_name: str,
        arguments: dict[str, Any],
        *,
        timeout: float = 30.0,
    ) -> Any:
        """Invoke ``tool_name`` on ``server_url`` with ``arguments``.

        Args:
            server_url: Connection URL of the target MCP server.
            tool_name: Name of the tool to invoke.
            arguments: Tool arguments (must match the tool's input schema).
            timeout: Per-call timeout in seconds (default 30s, per D-046 sandbox).

        Returns:
            The tool's result, as returned by the server.
        """
        # Validate arguments against the cached schema if available.
        schemas = self._tool_cache.get(server_url)
        if schemas:
            match = next((s for s in schemas if s.name == tool_name), None)
            if match and not validate_tool_args(match.input_schema, arguments):
                raise ValueError(f"Arguments for tool {tool_name!r} failed schema validation")

        transport = await self._get_transport(server_url)
        try:
            response = await asyncio.wait_for(
                transport.request("tools/call", {"name": tool_name, "arguments": arguments}),
                timeout=timeout,
            )
        except asyncio.TimeoutError as exc:
            raise TimeoutError(f"MCP tool {tool_name!r} timed out after {timeout}s") from exc
        return _parse_tool_result(response)

    # ----------------------------------------------------------- discovery

    async def discover_servers(self, tenant_id: str) -> list[MCPServerInfo]:
        """Discover all MCP servers registered for ``tenant_id``."""
        return await self._registry.list_servers(tenant_id)


# ---------------------------------------------------------------------------
# Response parsing
# ---------------------------------------------------------------------------


def _parse_tools_response(response: Any) -> list[ToolSchema]:
    """Parse a ``tools/list`` response into ToolSchema objects.

    Accepts both the dict shape produced by :mod:`ai_mcp.transport` (which
    mirrors the SDK's ``ListToolsResult``) and raw JSON-RPC envelopes for
    backwards compatibility with test doubles.
    """
    if isinstance(response, dict):
        if "result" in response and isinstance(response["result"], dict):
            response = response["result"]
        elif "error" in response:
            raise RuntimeError(f"MCP error: {response['error']}")
    tools = response.get("tools", []) if isinstance(response, dict) else []
    schemas: list[ToolSchema] = []
    for t in tools:
        schemas.append(
            ToolSchema(
                name=t.get("name", ""),
                description=sanitize_tool_description(t.get("description", "")),
                input_schema=t.get("input_schema", {"type": "object", "properties": {}}),
                output_schema=t.get("output_schema"),
            )
        )
    return schemas


def _parse_tool_result(response: Any) -> Any:
    """Parse a ``tools/call`` response."""
    if isinstance(response, dict):
        if "error" in response:
            raise RuntimeError(f"MCP tool error: {response['error']}")
        if "result" in response and not isinstance(response["result"], (str, list, dict)):
            # SDK-style envelope handled by transport already; skip.
            pass
        elif "result" in response:
            return response["result"]
    return response


__all__ = ["MCPClient"]
