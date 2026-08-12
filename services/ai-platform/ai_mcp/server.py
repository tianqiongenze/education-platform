"""EPMCPServer — exposes SP-04 AI capabilities as MCP tools.

Uses the **official MCP Python SDK** (``mcp.server.Server`` / FastMCP) to
define tools via the ``@server.tool()`` decorator. The SDK introspects each
function's docstring and type annotations to auto-generate the JSON Schema
exposed over the protocol, so complete docstrings and precise annotations
are mandatory.

The SDK is a hard dependency (declared in ``pyproject.toml``); there is no
hand-written fallback registry. We still keep an in-memory index of
registered tool callables so unit tests can invoke them directly via
:meth:`call_tool` without spinning up a full MCP transport.
"""

from __future__ import annotations

import inspect
import logging
from dataclasses import dataclass
from typing import Any, Callable

from ai_mcp.schema import ToolSchema, sanitize_tool_description

logger = logging.getLogger(__name__)


@dataclass
class _ToolEntry:
    name: str
    func: Callable[..., Any]
    schema: ToolSchema


class EPMCPServer:
    """Host-side MCP server exposing SP-04 AI tools.

    Tools are registered via :meth:`tool` (which mirrors the SDK's decorator
    API) and forwarded to the underlying ``mcp.server.Server`` so they are
    advertised over the wire when the server runs.
    """

    def __init__(self, name: str = "ai-platform-mcp", version: str = "1.0.0") -> None:
        self.name = name
        self.version = version
        self._tools: dict[str, _ToolEntry] = {}
        from mcp.server import Server

        self._backend: Any = Server(name)

    # -------------------------------------------------------------- tool API

    def tool(self, name: str | None = None, description: str | None = None) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        """Register a function as an MCP tool.

        The function is registered on the underlying ``mcp.server.Server`` so
        it is advertised over the MCP protocol, and also indexed locally for
        direct invocation via :meth:`call_tool`.
        """

        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            tool_name = name or func.__name__
            doc = description or func.__doc__ or ""
            schema = _schema_from_func(func, tool_name, doc)
            self._tools[tool_name] = _ToolEntry(name=tool_name, func=func, schema=schema)

            try:
                # Forward to the real SDK decorator so the tool is exposed
                # over the MCP protocol when the server runs.
                sdk_decorator = self._backend.tool(name=tool_name, description=sanitize_tool_description(doc))
                sdk_decorator(func)
            except Exception as exc:  # noqa: BLE001
                logger.warning("Failed to register tool %s with mcp SDK: %s", tool_name, exc)

            return func

        return decorator

    def list_tools(self) -> list[ToolSchema]:
        """Return the schemas of all registered tools."""
        return [entry.schema for entry in self._tools.values()]

    def get_tool(self, name: str) -> _ToolEntry | None:
        return self._tools.get(name)

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        """Invoke a registered tool by name with the given arguments."""
        entry = self._tools.get(name)
        if entry is None:
            raise KeyError(f"Unknown MCP tool: {name}")
        result = entry.func(**arguments)
        if inspect.isawaitable(result):
            result = await result
        return result

    @property
    def backend(self) -> Any:
        """The underlying ``mcp.server.Server``."""
        return self._backend


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _schema_from_func(func: Callable[..., Any], name: str, description: str) -> ToolSchema:
    """Build a JSON Schema for ``func``'s parameters from its annotations."""
    sig = inspect.signature(func)
    properties: dict[str, Any] = {}
    required: list[str] = []
    for param_name, param in sig.parameters.items():
        if param_name in ("self", "cls"):
            continue
        ann = param.annotation if param.annotation is not inspect.Parameter.empty else Any
        properties[param_name] = _json_type(ann)
        if param.default is inspect.Parameter.empty:
            required.append(param_name)
    input_schema: dict[str, Any] = {"type": "object", "properties": properties}
    if required:
        input_schema["required"] = required
    return ToolSchema(
        name=name,
        description=sanitize_tool_description(description),
        input_schema=input_schema,
    )


def _json_type(annotation: Any) -> dict[str, Any]:
    """Map a Python annotation to a JSON-Schema type descriptor."""
    mapping = {
        str: {"type": "string"},
        int: {"type": "integer"},
        float: {"type": "number"},
        bool: {"type": "boolean"},
        list: {"type": "array"},
        dict: {"type": "object"},
        type(None): {"type": "null"},
    }
    if annotation in mapping:
        return mapping[annotation]
    return {"type": "string", "description": "untyped"}
