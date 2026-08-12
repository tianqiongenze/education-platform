"""Hermes event schema."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any
from enum import Enum


class EventType(str, Enum):
    SUBMISSION_CREATED = "SUBMISSION_CREATED"
    GRADING_COMPLETED = "GRADING_COMPLETED"
    QUIZ_GENERATED = "QUIZ_GENERATED"
    LEARNPATH_UPDATED = "LEARNPATH_UPDATED"
    SESSION_ENDED = "SESSION_ENDED"
    TASK_STATUS_CHANGED = "TASK_STATUS_CHANGED"


@dataclass
class AgentEvent:
    event_type: str
    tenant_id: str | None = None
    agent_id: str | None = None
    task_id: str | None = None
    trace_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    payload: dict[str, Any] = field(default_factory=dict)
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> dict[str, Any]:
        return {
            "event_type": self.event_type,
            "tenant_id": self.tenant_id,
            "agent_id": self.agent_id,
            "task_id": self.task_id,
            "trace_id": self.trace_id,
            "payload": dict(self.payload),
            "timestamp": self.timestamp,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AgentEvent":
        return cls(
            event_type=data["event_type"],
            tenant_id=data.get("tenant_id"),
            agent_id=data.get("agent_id"),
            task_id=data.get("task_id"),
            trace_id=data.get("trace_id", uuid.uuid4().hex),
            payload=dict(data.get("payload", {})),
            timestamp=float(data.get("timestamp", time.time())),
        )
