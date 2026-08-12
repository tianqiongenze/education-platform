"""Task lifecycle management for A2A protocol."""

from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

import structlog

from .message import Artifact, Message

logger = structlog.get_logger(__name__)

TASK_TTL_SECONDS = 7 * 24 * 3600  # 7 days


class TaskStatus(str, Enum):
    SUBMITTED = "submitted"
    WORKING = "working"
    INPUT_REQUIRED = "input-required"
    COMPLETED = "completed"
    CANCELED = "canceled"
    FAILED = "failed"
    REJECTED = "rejected"


# Allowed state transitions (target -> set of allowed sources)
_TRANSITIONS: dict[TaskStatus, set[TaskStatus]] = {
    TaskStatus.WORKING: {TaskStatus.SUBMITTED, TaskStatus.WORKING, TaskStatus.INPUT_REQUIRED},
    TaskStatus.INPUT_REQUIRED: {TaskStatus.WORKING},
    TaskStatus.COMPLETED: {TaskStatus.WORKING, TaskStatus.INPUT_REQUIRED},
    TaskStatus.CANCELED: {TaskStatus.SUBMITTED, TaskStatus.WORKING, TaskStatus.INPUT_REQUIRED},
    TaskStatus.FAILED: {TaskStatus.WORKING, TaskStatus.INPUT_REQUIRED},
    TaskStatus.REJECTED: {TaskStatus.SUBMITTED},
}


@dataclass
class Task:
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    status: TaskStatus = TaskStatus.SUBMITTED
    message: Message | None = None
    artifacts: list[Artifact] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    tenant_id: str | None = None
    # LangGraph thread_id mapping (D-006 session externalization)
    thread_id: str | None = None
    error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "status": self.status.value,
            "message": self.message.to_dict() if self.message else None,
            "artifacts": [a.to_dict() for a in self.artifacts],
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "tenant_id": self.tenant_id,
            "thread_id": self.thread_id,
            "error": self.error,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Task":
        return cls(
            id=data["id"],
            status=TaskStatus(data.get("status", "submitted")),
            message=Message.from_dict(data["message"]) if data.get("message") else None,
            artifacts=[Artifact.from_dict(a) for a in data.get("artifacts", [])],
            created_at=float(data.get("created_at", time.time())),
            updated_at=float(data.get("updated_at", time.time())),
            tenant_id=data.get("tenant_id"),
            thread_id=data.get("thread_id"),
            error=data.get("error"),
        )


class TaskManager:
    """Manages task lifecycle with Redis persistence (key=a2a:task:{task_id})."""

    KEY_PREFIX = "a2a:task"

    def __init__(self, redis_client: Any | None = None) -> None:
        self._redis = redis_client
        self._local: dict[str, Task] = {}  # fallback when redis unavailable

    def _key(self, task_id: str) -> str:
        return f"{self.KEY_PREFIX}:{task_id}"

    @staticmethod
    def can_transition(current: TaskStatus, target: TaskStatus) -> bool:
        if current == target:
            return True
        return current in _TRANSITIONS.get(target, set())

    async def create(self, task: Task) -> Task:
        task.status = TaskStatus.SUBMITTED
        task.created_at = task.updated_at = time.time()
        await self._save(task)
        logger.info("task.created", task_id=task.id, tenant_id=task.tenant_id)
        return task

    async def get(self, task_id: str) -> Task | None:
        if self._redis is not None:
            raw = await self._redis.get(self._key(task_id))
            if raw is None:
                return None
            data = json.loads(raw)
            return Task.from_dict(data)
        return self._local.get(task_id)

    async def update(self, task_id: str, status: TaskStatus, *, message: Message | None = None,
                     artifacts: list[Artifact] | None = None, error: str | None = None) -> Task | None:
        task = await self.get(task_id)
        if task is None:
            return None
        if not self.can_transition(task.status, status):
            raise ValueError(f"Illegal transition {task.status} -> {status} for task {task_id}")
        task.status = status
        task.updated_at = time.time()
        if message is not None:
            task.message = message
        if artifacts is not None:
            task.artifacts = artifacts
        if error is not None:
            task.error = error
        await self._save(task)
        logger.info("task.updated", task_id=task.id, status=status.value)
        return task

    async def cancel(self, task_id: str) -> Task | None:
        return await self.update(task_id, TaskStatus.CANCELED)

    async def list_tasks(self, tenant_id: str | None = None) -> list[Task]:
        # Best-effort listing; in Redis we'd SCAN, here we filter local store.
        tasks = list(self._local.values())
        if tenant_id is not None:
            tasks = [t for t in tasks if t.tenant_id == tenant_id]
        return sorted(tasks, key=lambda t: t.created_at)

    async def _save(self, task: Task) -> None:
        if self._redis is not None:
            await self._redis.set(self._key(task.id), json.dumps(task.to_dict()), ex=TASK_TTL_SECONDS)
        else:
            self._local[task.id] = task
