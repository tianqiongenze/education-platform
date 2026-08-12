"""ep-memory data models."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from enum import Enum


class MessageRole(str, Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


class MemoryType(str, Enum):
    EPISODIC = "episodic"
    SEMANTIC = "semantic"
    PROCEDURAL = "procedural"


@dataclass
class Memory:
    """A single memory record stored in L2/L3."""

    id: str = field(default_factory=lambda: f"mem_{uuid.uuid4().hex[:12]}")
    tenant_id: str = ""
    user_id: str = ""
    content: str = ""
    memory_type: MemoryType = MemoryType.EPISODIC
    score: float = 1.0
    created_at: float = field(default_factory=time.time)
    last_accessed_at: float = field(default_factory=time.time)
    access_count: int = 0
    metadata: dict = field(default_factory=dict)

    def touch(self) -> None:
        """Update access tracking."""
        self.last_accessed_at = time.time()
        self.access_count += 1

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "tenant_id": self.tenant_id,
            "user_id": self.user_id,
            "content": self.content,
            "memory_type": self.memory_type.value,
            "score": self.score,
            "created_at": self.created_at,
            "last_accessed_at": self.last_accessed_at,
            "access_count": self.access_count,
            "metadata": self.metadata,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "Memory":
        mtype = data.get("memory_type", "episodic")
        if isinstance(mtype, str):
            mtype = MemoryType(mtype)
        return cls(
            id=data.get("id", f"mem_{uuid.uuid4().hex[:12]}"),
            tenant_id=data.get("tenant_id", ""),
            user_id=data.get("user_id", ""),
            content=data.get("content", ""),
            memory_type=mtype,
            score=data.get("score", 1.0),
            created_at=data.get("created_at", time.time()),
            last_accessed_at=data.get("last_accessed_at", time.time()),
            access_count=data.get("access_count", 0),
            metadata=data.get("metadata", {}),
        )


@dataclass
class ContextWindow:
    """Aggregated context window returned by MemoryManager.get_context()."""

    immediate_window: list[dict] = field(default_factory=list)
    working_summary: str = ""
    long_term_memories: list[Memory] = field(default_factory=list)
    org_memories: list[Memory] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "immediate_window": self.immediate_window,
            "working_summary": self.working_summary,
            "long_term_memories": [m.to_dict() for m in self.long_term_memories],
            "org_memories": [m.to_dict() for m in self.org_memories],
        }
