"""Transport-layer adapters for MCP communication.

This module provides thin :class:`Transport` wrappers around the **official
MCP Python SDK** (``mcp`` package). The SDK ships its own transport
implementations for stdio and Streamable HTTP; we wrap them so that:

* callers keep a uniform, transport-agnostic interface (``connect`` /
  ``close`` / ``request``);
* the :class:`~ai_mcp.client.MCPClient` can manage a pool of transports and
  inject test doubles (see ``tests/test_mcp.py``).

No JSON-RPC framing is hand-written here: every request is delegated to an
``mcp.ClientSession`` which speaks the protocol natively.
"""

from __future__ import annotations

import abc
import logging
from contextlib import AsyncExitStack
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


class Transport(abc.ABC):
    """Abstract base for MCP transport implementations.

    A :class:`Transport` owns an ``mcp.ClientSession`` once connected and
    exposes a ``request(method, params)`` coroutine that mirrors the SDK's
    JSON-RPC request semantics. Test doubles may override ``request`` to
    return canned responses without touching the network.
    """

    @abc.abstractmethod
    async def connect(self) -> None:
        """Establish the underlying connection / spawn the child process and
        open an ``mcp.ClientSession`` on top of it."""

    @abc.abstractmethod
    async def close(self) -> None:
        """Tear down the session and the underlying connection."""

    @abc.abstractmethod
    async def request(self, method: str, params: dict[str, Any] | None = None) -> Any:
        """Send a JSON-RPC request through the SDK ``ClientSession`` and
        return the raw result payload."""

    @property
    @abc.abstractmethod
    def is_connected(self) -> bool:
        ...


@dataclass
class StdioTransport(Transport):
    """Stdio transport backed by ``mcp.client.stdio.stdio_client``.

    Spawns an MCP server as a child process and communicates over
    stdin/stdout using the SDK's native stdio transport. The SDK handles
    JSON-RPC framing, message boundaries, and protocol version negotiation.
    """

    command: str
    args: list[str] | None = None
    env: dict[str, str] | None = None

    _stack: AsyncExitStack | None = field(default=None, init=False, repr=False)
    _session: Any | None = field(default=None, init=False, repr=False)
    _connected: bool = field(default=False, init=False, repr=False)

    async def connect(self) -> None:
        if self._connected and self._session is not None:
            return
        from mcp import ClientSession
        from mcp.client.stdio import StdioServerParameters, stdio_client

        params = StdioServerParameters(
            command=self.command,
            args=list(self.args or []),
            env={**__import__("os").environ, **(self.env or {})} if self.env else None,
        )
        self._stack = AsyncExitStack()
        read, write = await self._stack.enter_async_context(stdio_client(params))
        self._session = await self._stack.enter_async_context(ClientSession(read, write))
        await self._session.initialize()
        self._connected = True
        logger.info("Started stdio MCP server: %s %s", self.command, self.args or [])

    async def close(self) -> None:
        if self._stack is not None:
            await self._stack.aclose()
            self._stack = None
        self._session = None
        self._connected = False

    async def request(self, method: str, params: dict[str, Any] | None = None) -> Any:
        if self._session is None:
            raise RuntimeError("StdioTransport not connected")
        return await _session_request(self._session, method, params)

    @property
    def is_connected(self) -> bool:
        return self._connected and self._session is not None


@dataclass
class HTTPTransport(Transport):
    """Streamable HTTP transport backed by
    ``mcp.client.streamable_http.streamable_http_client``.

    The SDK owns the HTTP connection, JSON-RPC framing, and the SSE-style
    streaming semantics of the Streamable HTTP transport.
    """

    url: str
    headers: dict[str, str] | None = None
    timeout: float = 30.0

    _stack: AsyncExitStack | None = field(default=None, init=False, repr=False)
    _session: Any | None = field(default=None, init=False, repr=False)
    _connected: bool = field(default=False, init=False, repr=False)

    async def connect(self) -> None:
        if self._connected and self._session is not None:
            return
        from mcp import ClientSession
        from mcp.client.streamable_http import streamable_http_client

        self._stack = AsyncExitStack()
        kwargs: dict[str, Any] = {"terminate_on_close": True}
        if self.headers:
            # The SDK accepts a pre-configured httpx AsyncClient for custom
            # headers / auth / timeouts.
            import httpx

            kwargs["http_client"] = httpx.AsyncClient(headers=self.headers, timeout=self.timeout)
        read, write = await self._stack.enter_async_context(streamable_http_client(self.url, **kwargs))
        self._session = await self._stack.enter_async_context(ClientSession(read, write))
        await self._session.initialize()
        self._connected = True

    async def close(self) -> None:
        if self._stack is not None:
            await self._stack.aclose()
            self._stack = None
        self._session = None
        self._connected = False

    async def request(self, method: str, params: dict[str, Any] | None = None) -> Any:
        if self._session is None:
            raise RuntimeError("HTTPTransport not connected")
        return await _session_request(self._session, method, params)

    @property
    def is_connected(self) -> bool:
        return self._connected and self._session is not None


def select_transport(server_url: str, **kwargs: Any) -> Transport:
    """Pick a transport implementation based on the server URL scheme.

    Args:
        server_url: An ``http(s)://`` URL (Streamable HTTP) or an executable
            command path (stdio).
        **kwargs: Forwarded to the chosen transport constructor.

    Returns:
        A :class:`Transport` instance (not yet connected).
    """
    if server_url.startswith(("http://", "https://")):
        return HTTPTransport(url=server_url, **kwargs)
    # Treat as a stdio command. ``server_url`` is the executable; any
    # additional arguments should be supplied via ``args`` in kwargs.
    args = kwargs.pop("args", None)
    env = kwargs.pop("env", None)
    return StdioTransport(command=server_url, args=args, env=env, **kwargs)


# ---------------------------------------------------------------------------
# SDK session bridging
# ---------------------------------------------------------------------------


async def _session_request(session: Any, method: str, params: dict[str, Any] | None = None) -> Any:
    """Route a JSON-RPC ``method`` to the corresponding ``ClientSession`` API.

    The MCP ``ClientSession`` exposes high-level coroutines (``list_tools``,
    ``call_tool``) rather than a generic ``request``. We map the two legacy
    method names used by :class:`~ai_mcp.client.MCPClient` so callers keep a
    familiar JSON-RPC-like surface while the SDK does the real work.
    """
    params = params or {}
    if method == "tools/list":
        result = await session.list_tools()
        # ``ListToolsResult`` exposes ``.tools`` (list[Tool]); serialise to the
        # dict shape the rest of the codebase expects.
        return {"tools": [_tool_to_dict(t) for t in result.tools]}
    if method == "tools/call":
        result = await session.call_tool(params.get("name", ""), params.get("arguments", {}))
        return _call_result_to_dict(result)
    # Unknown method: let the session send a raw request if possible.
    if hasattr(session, "send_request"):
        return await session.send_request({"method": method, "params": params})
    raise ValueError(f"Unsupported MCP method: {method}")


def _tool_to_dict(tool: Any) -> dict[str, Any]:
    """Convert an ``mcp.types.Tool`` to the dict shape used by ToolSchema."""
    return {
        "name": getattr(tool, "name", ""),
        "description": getattr(tool, "description", "") or "",
        "input_schema": getattr(tool, "input_schema", None) or {"type": "object", "properties": {}},
        "output_schema": getattr(tool, "output_schema", None),
    }


def _call_result_to_dict(result: Any) -> Any:
    """Convert an ``mcp.types.CallToolResult`` to a plain payload.

    If the server returned structured content we surface it directly;
    otherwise we extract the textual content for backwards compatibility.
    """
    structured = getattr(result, "structured_content", None)
    if structured is not None:
        return structured
    content = getattr(result, "content", None) or []
    texts: list[str] = []
    for item in content:
        text = getattr(item, "text", None)
        if text is not None:
            texts.append(text)
    if len(texts) == 1:
        return texts[0]
    if texts:
        return texts
    return None
