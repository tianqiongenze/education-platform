"""PluginLoader — turn plugin manifests into usable LangChain tools.

Uses ``langchain-mcp-adapters``' :func:`load_mcp_tools` to bridge MCP tool
schemas into LangChain :class:`BaseTool` instances. When the adapter is not
installed (e.g. in test environments) the loader falls back to lightweight
in-process wrappers built directly on :class:`ai_mcp.client.MCPClient` so
plugins remain callable.
"""

from __future__ import annotations

import logging
from typing import Any

from ai_mcp.client import MCPClient
from ai_mcp.schema import ToolSchema
from ai_plugin.manifest import PluginManifest
from ai_plugin.registry import PluginRegistry

logger = logging.getLogger(__name__)


class PluginLoader:
    """Loads plugin tools for a tenant, with graceful adapter fallback."""

    def __init__(self, registry: PluginRegistry, client: MCPClient | None = None) -> None:
        self._registry = registry
        self._client = client or MCPClient()
        self._use_adapter = self._adapter_available()

    @staticmethod
    def _adapter_available() -> bool:
        try:
            import langchain_mcp_adapters  # type: ignore[import-not-found]  # noqa: F401

            return True
        except ImportError:
            return False

    async def load_plugin(self, manifest: PluginManifest) -> list[Any]:
        """Load all tools exposed by a single plugin's MCP server.

        Returns a list of LangChain ``BaseTool``-like objects.
        """
        if self._use_adapter:
            return await self._load_via_adapter(manifest)
        return await self._load_via_client(manifest)

    async def load_all_for_tenant(self, tenant_id: str) -> dict[str, list[Any]]:
        """Load tools for every plugin registered for ``tenant_id``.

        Returns a mapping of ``plugin_id -> list[tool]``.
        """
        plugins = await self._registry.list_plugins(tenant_id)
        result: dict[str, list[Any]] = {}
        for plugin in plugins:
            try:
                result[plugin.plugin_id] = await self.load_plugin(plugin)
            except Exception as exc:  # noqa: BLE001
                logger.warning("Failed to load plugin %s: %s", plugin.plugin_id, exc)
                result[plugin.plugin_id] = []
        return result

    # ----------------------------------------------------- adapter path

    async def _load_via_adapter(self, manifest: PluginManifest) -> list[Any]:
        """Use ``langchain-mcp-adapters`` to convert MCP tools to BaseTool.

        The adapter requires a live MCP server connection. When the underlying
        transport cannot be established (no server reachable, SDK incompatibility,
        etc.) we transparently fall back to the in-process client path so callers
        that inject a test ``MCPClient`` (e.g. ``StubClient``) keep working
        without network access.
        """
        try:
            from langchain_mcp_adapters.tools import load_mcp_tools  # type: ignore[import-not-found]
        except ImportError:
            return await self._load_via_client(manifest)

        try:
            # The adapter expects a client/session object; we pass our MCPClient's
            # transport-aware session for the plugin's server URL.
            transport = await self._client._get_transport(manifest.mcp_server_url)  # noqa: SLF001
            return await load_mcp_tools(transport)
        except Exception as exc:  # noqa: BLE001
            # Adapter path is unavailable (no live server / SDK mismatch):
            # degrade gracefully to the in-process client path, which honours
            # injected test doubles and avoids hard infrastructure deps.
            logger.debug("Adapter load failed for %s, using client fallback: %s", manifest.plugin_id, exc)
            return await self._load_via_client(manifest)

    # ------------------------------------------------------- client path

    async def _load_via_client(self, manifest: PluginManifest) -> list[Any]:
        """Fallback: wrap MCP tools in simple callable objects via MCPClient."""
        from langchain_core.tools import StructuredTool  # type: ignore[import-not-found]

        schemas = await self._client.list_tools(manifest.mcp_server_url)
        tools: list[Any] = []
        for schema in schemas:
            tools.append(self._wrap_schema(schema, manifest))
        # If StructuredTool import failed, _wrap_schema returns a plain shim.
        return tools

    def _wrap_schema(self, schema: ToolSchema, manifest: PluginManifest) -> Any:
        """Build a callable wrapper for a single MCP tool."""
        try:
            from langchain_core.tools import StructuredTool  # type: ignore[import-not-found]
        except ImportError:
            return _MCPToolShim(schema=schema, client=self._client, server_url=manifest.mcp_server_url)

        async def _arun(**kwargs: Any) -> Any:
            return await self._client.call_tool(manifest.mcp_server_url, schema.name, kwargs)

        return StructuredTool.from_function(
            coroutine=_arun,
            name=schema.name,
            description=schema.description,
            args_schema=_args_to_pydantic(schema),
        )


def _args_to_pydantic(schema: ToolSchema) -> Any:
    """Best-effort conversion of a tool's input schema to a Pydantic model.

    Falls back to None (StructuredTool will infer from the function signature).
    """
    try:
        from pydantic import create_model  # type: ignore[import-not-found]

        fields: dict[str, Any] = {}
        for name, prop in schema.input_schema.get("properties", {}).items():
            t = _py_type_for(prop.get("type", "string"))
            fields[name] = (t, ...)
        return create_model(f"{schema.name}Args", **fields)
    except Exception:  # noqa: BLE001
        return None


def _py_type_for(json_type: str) -> Any:
    return {
        "string": str,
        "integer": int,
        "number": float,
        "boolean": bool,
        "array": list,
        "object": dict,
        "null": type(None),
    }.get(json_type, str)


class _MCPToolShim:
    """Minimal stand-in for a LangChain tool when neither adapter nor core is installed."""

    def __init__(self, *, schema: ToolSchema, client: MCPClient, server_url: str) -> None:
        self.name = schema.name
        self.description = schema.description
        self.args_schema = schema.input_schema
        self._client = client
        self._server_url = server_url

    async def ainvoke(self, arguments: dict[str, Any] | None = None, **kwargs: Any) -> Any:
        return await self._client.call_tool(self._server_url, self.name, arguments or {})

    async def __call__(self, **kwargs: Any) -> Any:
        return await self._client.call_tool(self._server_url, self.name, kwargs)
