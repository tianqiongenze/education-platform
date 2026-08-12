"""Agent Card model (Google A2A /.well-known/agent.json)."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any


@dataclass
class Skill:
    """An agent skill declaration."""

    id: str
    name: str
    description: str
    tags: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "name": self.name, "description": self.description, "tags": list(self.tags)}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Skill":
        return cls(
            id=data["id"],
            name=data["name"],
            description=data.get("description", ""),
            tags=list(data.get("tags", [])),
        )


@dataclass
class AgentCard:
    """Self-describing agent card published at /.well-known/agent.json."""

    name: str
    description: str
    version: str
    url: str
    capabilities: list[str] = field(default_factory=list)
    skills: list[Skill] = field(default_factory=list)
    authentication: dict[str, Any] | None = None
    tenant_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "version": self.version,
            "url": self.url,
            "capabilities": list(self.capabilities),
            "skills": [s.to_dict() for s in self.skills],
            "authentication": self.authentication,
            "tenant_id": self.tenant_id,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AgentCard":
        return cls(
            name=data["name"],
            description=data.get("description", ""),
            version=data.get("version", "0.0.1"),
            url=data["url"],
            capabilities=list(data.get("capabilities", [])),
            skills=[Skill.from_dict(s) for s in data.get("skills", [])],
            authentication=data.get("authentication"),
            tenant_id=data.get("tenant_id"),
        )

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)
