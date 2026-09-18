"""Plugin system for AI AI Middle-Platform.

A plugin bundles an MCP server URL with a manifest (permissions, tenant
whitelist, skills) so the host agent can safely load third-party capabilities
with multi-tenant isolation.
"""

from __future__ import annotations

from ai_plugin.discovery import PluginToolDiscovery
from ai_plugin.loader import PluginLoader
from ai_plugin.manifest import PluginManifest, PluginPermission
from ai_plugin.registry import PluginRegistry
from ai_plugin.sandbox import PluginSandbox

__all__ = [
    "PluginManifest",
    "PluginPermission",
    "PluginRegistry",
    "PluginLoader",
    "PluginSandbox",
    "PluginToolDiscovery",
]
