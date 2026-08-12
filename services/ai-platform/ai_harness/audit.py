"""AuditLogger — records all agent and tool events to Redis Stream → Iceberg.

D-046 Harness Audit: every agent invocation, tool call, budget breach, and
reflexion retry is logged to a Redis Stream for real-time monitoring and
async archival to Apache Iceberg (via Pulsar → Flink → Iceberg pipeline).

Iron Law D-033: if Redis is unavailable, falls back to local JSONL file.
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any

import structlog

logger = structlog.get_logger(__name__)

_AUDIT_STREAM = "audit:agent:events"
_AUDIT_FALLBACK_FILE = Path("/tmp/ep_audit_fallback.jsonl")


@dataclass
class AuditEntry:
    """A single audit event."""

    tenant_id: str
    agent: str
    event: str  # started, completed, timeout, error, tool_call, budget_breach, reflexion_retry
    detail: str = ""
    elapsed_sec: float = 0.0
    timestamp: float = field(default_factory=time.time)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)


class AuditLogger:
    """Logs audit events to Redis Stream with local fallback.

    Usage:
        audit = AuditLogger(redis=redis_client)
        await audit.log(AuditEntry(tenant_id="t1", agent="grading", event="completed"))
    """

    def __init__(self, redis: Any = None, fallback_file: Path | None = None) -> None:
        self._redis = redis
        self._fallback_file = fallback_file or _AUDIT_FALLBACK_FILE
        self._local_buffer: list[AuditEntry] = []

    async def log(self, entry: AuditEntry) -> None:
        """Log an audit entry to Redis Stream, fallback to local file."""
        if self._redis:
            try:
                await self._redis.xadd(_AUDIT_STREAM, {"data": entry.to_json()})
                return
            except Exception as e:
                logger.warning("audit_redis_failed", error=str(e), event=entry.event)

        # Fallback: local file
        self._local_buffer.append(entry)
        try:
            self._fallback_file.parent.mkdir(parents=True, exist_ok=True)
            with open(self._fallback_file, "a") as f:
                f.write(entry.to_json() + "\n")
        except Exception as e:
            logger.error("audit_fallback_failed", error=str(e))

    async def log_tool(
        self,
        tenant_id: str,
        agent: str,
        tool_name: str,
        status: str,  # success, error, blocked
        error: str = "",
    ) -> None:
        """Convenience method for logging tool calls."""
        await self.log(AuditEntry(
            tenant_id=tenant_id,
            agent=agent,
            event="tool_call",
            detail=f"{tool_name}:{status}",
            metadata={"tool": tool_name, "status": status, "error": error},
        ))

    async def log_reflexion(self, tenant_id: str, agent: str, trial: int, verdict: str) -> None:
        """Log a reflexion trial."""
        await self.log(AuditEntry(
            tenant_id=tenant_id,
            agent=agent,
            event="reflexion_retry",
            detail=f"trial={trial} verdict={verdict}",
            metadata={"trial": trial, "verdict": verdict},
        ))

    async def log_budget_breach(
        self, tenant_id: str, agent: str, model: str, detail: str
    ) -> None:
        """Log a budget breach."""
        await self.log(AuditEntry(
            tenant_id=tenant_id,
            agent=agent,
            event="budget_breach",
            detail=detail,
            metadata={"model": model},
        ))

    async def flush_buffer_to_redis(self) -> int:
        """Attempt to flush locally buffered entries to Redis. Returns count flushed."""
        if not self._redis or not self._local_buffer:
            return 0

        flushed = 0
        remaining: list[AuditEntry] = []
        for entry in self._local_buffer:
            try:
                await self._redis.xadd(_AUDIT_STREAM, {"data": entry.to_json()})
                flushed += 1
            except Exception:
                remaining.append(entry)

        self._local_buffer = remaining
        if flushed:
            logger.info("audit_buffer_flushed", count=flushed, remaining=len(remaining))
        return flushed
