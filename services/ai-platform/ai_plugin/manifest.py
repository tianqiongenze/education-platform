"""Plugin manifest — declarative description of a plugin.

A manifest is the single source of truth for what a plugin is, where its MCP
server lives, what it may do, and which tenants may use it. Manifests are
serialized to JSON for Redis caching and to YAML for the local fallback.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class PluginPermission(str, Enum):
    """Capabilities a plugin may request from the host.

    Industry-specific permissions (SUBMISSION_GRADE, QUIZ_GENERATE,
    LEARNING_PATH) are retained for backward compatibility with existing
    education plugins. New industries should use ``TASK_EXECUTE`` with a
    domain qualifier rather than adding new enum values.
    """

    KNOWLEDGE_READ = "knowledge:read"
    KNOWLEDGE_WRITE = "knowledge:write"
    SUBMISSION_GRADE = "submission:grade"
    QUIZ_GENERATE = "quiz:generate"
    LEARNING_PATH = "learning:path"
    NETWORK_EGRESS = "network:egress"
    FILESYSTEM = "filesystem"
    # Generic task execution — industry-agnostic. New industries use this with
    # a domain qualifier instead of adding industry-specific permissions.
    TASK_EXECUTE = "task:execute"


@dataclass
class PluginSkill:
    """A named capability advertised by a plugin."""

    name: str
    description: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {"name": self.name, "description": self.description}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PluginSkill:
        return cls(name=data["name"], description=data.get("description", ""))


@dataclass
class PluginManifest:
    """Declarative description of a plugin.

    Attributes:
        plugin_id: Stable unique identifier.
        name: Human-readable name.
        mcp_server_url: URL (stdio path or http(s)) of the plugin's MCP server.
        transport: Transport hint — ``stdio`` or ``http``.
        skills: Capabilities advertised by the plugin.
        required_permissions: Permissions the host must grant before loading.
        tenant_whitelist: Tenants allowed to use the plugin (``["*"]`` = all).
        version: Semver version string.
        description: Short human-readable description.
    """

    plugin_id: str
    name: str
    mcp_server_url: str
    transport: str = "http"
    skills: list[PluginSkill] = field(default_factory=list)
    required_permissions: list[PluginPermission] = field(default_factory=list)
    tenant_whitelist: list[str] = field(default_factory=lambda: ["*"])
    version: str = "1.0.0"
    description: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "plugin_id": self.plugin_id,
            "name": self.name,
            "mcp_server_url": self.mcp_server_url,
            "transport": self.transport,
            "skills": [s.to_dict() for s in self.skills],
            "required_permissions": [p.value for p in self.required_permissions],
            "tenant_whitelist": self.tenant_whitelist,
            "version": self.version,
            "description": self.description,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PluginManifest:
        perms_raw = data.get("required_permissions", [])
        # Tolerate both enum values and PluginPermission instances.
        perms: list[PluginPermission] = []
        for p in perms_raw:
            if isinstance(p, PluginPermission):
                perms.append(p)
            else:
                try:
                    perms.append(PluginPermission(str(p)))
                except ValueError:
                    pass
        return cls(
            plugin_id=data["plugin_id"],
            name=data.get("name", data["plugin_id"]),
            mcp_server_url=data["mcp_server_url"],
            transport=data.get("transport", "http"),
            skills=[PluginSkill.from_dict(s) for s in data.get("skills", [])],
            required_permissions=perms,
            tenant_whitelist=data.get("tenant_whitelist", ["*"]),
            version=data.get("version", "1.0.0"),
            description=data.get("description", ""),
        )

    def is_allowed_for_tenant(self, tenant_id: str) -> bool:
        """Return True if ``tenant_id`` may use this plugin."""
        return "*" in self.tenant_whitelist or tenant_id in self.tenant_whitelist
