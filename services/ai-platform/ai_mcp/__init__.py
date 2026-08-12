"""MCP (Model Context Protocol) layer for SP-04 AI Middle-Platform.

Provides the protocol-level primitives for exposing SP-04 AI capabilities as
MCP tools, discovering remote MCP servers, and routing calls across multiple
transports (stdio / Streamable HTTP) in a multi-tenant environment.
"""

from __future__ import annotations

from ai_mcp.client import MCPClient
from ai_mcp.registry import MCPServerRegistry
from ai_mcp.schema import ToolSchema, sanitize_tool_description, validate_tool_args
from ai_mcp.server import EPMCPServer
from ai_mcp.transport import HTTPTransport, StdioTransport, Transport, select_transport

__all__ = [
    "EPMCPServer",
    "MCPClient",
    "MCPServerRegistry",
    "ToolSchema",
    "sanitize_tool_description",
    "validate_tool_args",
    "HTTPTransport",
    "StdioTransport",
    "Transport",
    "select_transport",
]
