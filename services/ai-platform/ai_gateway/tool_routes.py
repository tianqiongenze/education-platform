"""MCP tool routes — expose MCP server registry and tool discovery.

Endpoints:
    GET  /v1/tools          — List all available MCP tools (cross-server)
    GET  /v1/tools/{name}   — Get tool schema
    POST /v1/tools/{name}/invoke — Invoke an MCP tool
    GET  /v1/mcp/servers    — List registered MCP servers
    POST /v1/mcp/servers     — Register a new MCP server
"""

from __future__ import annotations

from litestar import Router, get, post
from litestar.exceptions import HTTPException
from pydantic import BaseModel

from ai_mcp.registry import MCPServerRegistry, MCPServerInfo
from ai_agent.industry_config import load_industry_config


_registry = MCPServerRegistry()


class MCPServerRegisterRequest(BaseModel):
    server_id: str
    name: str = ""
    url: str
    transport: str = "http"  # stdio | http
    tenant_id: str = "*"
    version: str = "1.0.0"
    description: str = ""
    tools: list[str] = []


class ToolInvokeRequest(BaseModel):
    arguments: dict = {}
    tenant_id: str = "default"


@get("/v1/mcp/servers", status_code=200)
async def list_mcp_servers(tenant_id: str = "*") -> dict:
    """List all registered MCP servers for a tenant."""
    servers = await _registry.list_servers(tenant_id)
    return {"servers": [s.to_dict() for s in servers]}


@post("/v1/mcp/servers", status_code=201)
async def register_mcp_server(data: MCPServerRegisterRequest) -> dict:
    """Register a new MCP server."""
    info = MCPServerInfo(
        server_id=data.server_id,
        name=data.name or data.server_id,
        url=data.url,
        transport=data.transport,
        tenant_id=data.tenant_id,
        version=data.version,
        description=data.description,
        tools=data.tools,
    )
    await _registry.register(data.tenant_id, info)
    return {"status": "registered", "server_id": data.server_id}


@get("/v1/tools", status_code=200)
async def list_tools(tenant_id: str = "*") -> dict:
    """List all available MCP tools across all registered servers."""
    servers = await _registry.list_servers(tenant_id)
    # Aggregate tool names from all registered servers.
    tools: list[dict] = []
    for srv in servers:
        for tool_name in srv.tools:
            tools.append({"name": tool_name, "server": srv.server_id, "description": ""})
    # If no servers registered, fall back to the industry config's tool catalog.
    if not tools:
        cfg = load_industry_config()
        for t in cfg.tool_catalog:
            tools.append({"name": t.name, "server": t.server, "description": t.description})
    return {"tools": tools}


@get("/v1/tools/{name:str}", status_code=200)
async def get_tool_schema(name: str) -> dict:
    """Get the JSON schema for a specific tool.

    The catalog is built dynamically from the industry config. Tools not in the
    config (e.g. registered dynamically via MCP servers) are still discoverable
    via ``list_tools`` but have no static schema here.
    """
    cfg = load_industry_config()
    for t in cfg.tool_catalog:
        if t.name == name:
            return {
                "name": t.name,
                "description": t.description,
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                },
            }
    raise HTTPException(status_code=404, detail=f"Tool '{name}' not found")


@post("/v1/tools/{name:str}/invoke", status_code=200)
async def invoke_tool(name: str, data: ToolInvokeRequest) -> dict:
    """Invoke an MCP tool by name.

    In production, this routes to the appropriate MCP server via MCPClient.
    For now, returns a mock result.
    """
    return {
        "tool": name,
        "status": "executed",
        "result": f"Mock result for tool '{name}' with args {data.arguments}",
        "tenant_id": data.tenant_id,
    }


tool_router = Router(
    path="/",
    route_handlers=[list_mcp_servers, register_mcp_server, list_tools, get_tool_schema, invoke_tool],
)
