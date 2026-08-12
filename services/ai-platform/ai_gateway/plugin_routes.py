"""Plugin system routes — manage MCP-based plugins.

Endpoints:
    GET    /v1/plugins           — List all plugins for a tenant
    POST   /v1/plugins/install   — Install a plugin from manifest
    DELETE /v1/plugins/{name}    — Uninstall a plugin
    GET    /v1/plugins/{name}    — Get plugin manifest
    POST   /v1/plugins/{name}/enable  — Enable a plugin for a tenant
    POST   /v1/plugins/{name}/disable — Disable a plugin for a tenant
"""

from __future__ import annotations

from litestar import Router, delete, get, post
from litestar.exceptions import HTTPException
from pydantic import BaseModel

from ai_plugin.manifest import PluginManifest, PluginPermission, PluginSkill
from ai_plugin.registry import PluginRegistry


_registry = PluginRegistry()


class PluginSkillDTO(BaseModel):
    name: str
    description: str = ""


class PluginInstallRequest(BaseModel):
    plugin_id: str
    name: str
    mcp_server_url: str
    transport: str = "http"
    skills: list[PluginSkillDTO] = []
    required_permissions: list[str] = []
    tenant_whitelist: list[str] = ["*"]
    version: str = "1.0.0"
    description: str = ""


class PluginTenantRequest(BaseModel):
    tenant_id: str


def _build_manifest(data: PluginInstallRequest) -> PluginManifest:
    """Construct a PluginManifest from the request DTO."""
    perms: list[PluginPermission] = []
    for p in data.required_permissions:
        try:
            perms.append(PluginPermission(p))
        except ValueError:
            pass
    return PluginManifest(
        plugin_id=data.plugin_id,
        name=data.name,
        mcp_server_url=data.mcp_server_url,
        transport=data.transport,
        skills=[PluginSkill(name=s.name, description=s.description) for s in data.skills],
        required_permissions=perms,
        tenant_whitelist=data.tenant_whitelist,
        version=data.version,
        description=data.description,
    )


def _plugin_to_dict(m: PluginManifest) -> dict:
    return {
        "plugin_id": m.plugin_id,
        "name": m.name,
        "mcp_server_url": m.mcp_server_url,
        "transport": m.transport,
        "skills": [s.to_dict() for s in m.skills],
        "required_permissions": [p.value if hasattr(p, "value") else str(p) for p in m.required_permissions],
        "tenant_whitelist": m.tenant_whitelist,
        "version": m.version,
        "description": m.description,
    }


@get("/v1/plugins", status_code=200)
async def list_plugins(tenant_id: str = "*") -> dict:
    """List all plugins visible to ``tenant_id``."""
    plugins = await _registry.list_plugins(tenant_id)
    return {"plugins": [_plugin_to_dict(p) for p in plugins]}


@post("/v1/plugins/install", status_code=201)
async def install_plugin(data: PluginInstallRequest) -> dict:
    """Install a plugin from a manifest."""
    manifest = _build_manifest(data)
    await _registry.register(manifest)
    return {"status": "installed", "plugin_id": data.plugin_id, "version": data.version}


@delete("/v1/plugins/{name:str}", status_code=200)
async def uninstall_plugin(name: str, tenant_id: str = "*") -> dict:
    """Uninstall a plugin."""
    existing = await _registry.get_plugin(name, tenant_id)
    if not existing:
        raise HTTPException(status_code=404, detail=f"Plugin '{name}' not found")
    await _registry.unregister(name, tenant_id)
    return {"status": "uninstalled", "plugin_id": name}


@get("/v1/plugins/{name:str}", status_code=200)
async def get_plugin(name: str, tenant_id: str = "*") -> dict:
    """Get a plugin's manifest."""
    manifest = await _registry.get_plugin(name, tenant_id)
    if not manifest:
        raise HTTPException(status_code=404, detail=f"Plugin '{name}' not found")
    return _plugin_to_dict(manifest)


@post("/v1/plugins/{name:str}/enable", status_code=200)
async def enable_plugin(name: str, data: PluginTenantRequest) -> dict:
    """Enable a plugin for a specific tenant by re-registering into that tenant's scope."""
    manifest = await _registry.get_plugin(name, "*")
    if not manifest:
        raise HTTPException(status_code=404, detail=f"Plugin '{name}' not found")
    if data.tenant_id not in manifest.tenant_whitelist:
        manifest.tenant_whitelist.append(data.tenant_id)
        await _registry.register(manifest)
    return {"status": "enabled", "plugin_id": name, "tenant_id": data.tenant_id}


@post("/v1/plugins/{name:str}/disable", status_code=200)
async def disable_plugin(name: str, data: PluginTenantRequest) -> dict:
    """Disable a plugin for a specific tenant by removing it from the whitelist."""
    manifest = await _registry.get_plugin(name, "*")
    if not manifest:
        raise HTTPException(status_code=404, detail=f"Plugin '{name}' not found")
    if data.tenant_id in manifest.tenant_whitelist:
        manifest.tenant_whitelist.remove(data.tenant_id)
        await _registry.register(manifest)
    return {"status": "disabled", "plugin_id": name, "tenant_id": data.tenant_id}


plugin_router = Router(
    path="/",
    route_handlers=[list_plugins, install_plugin, uninstall_plugin, get_plugin, enable_plugin, disable_plugin],
)
