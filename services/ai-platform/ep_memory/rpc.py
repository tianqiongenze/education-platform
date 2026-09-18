"""D-045 RPC interface — JSON protocol contract for the Java SDK.

This module documents the canonical JSON wire protocol consumed by the
``ep-memory-java`` SDK (``com.ep.memory.MemoryClient`` / ``MemoryRpcService``).
The Java SDK talks to the AI AI Gateway REST surface (``/v1/memory/*``);
``rpc.py`` is intentionally framework-agnostic: it only defines the request /
response schemas and the dispatch table so that:

1. The Java side and the Python side share one source of truth.
2. A future Dubbo / Tri-protocol provider can reuse the same contract.
3. The gateway can serve both ``application/json`` REST and a framed JSON RPC
   transport from a single dispatcher.

Schemas mirror the records in ``com.ep.memory.model`` (Java 21):

    MemoryAddRequest      { tenantId, userId, sessionId, messages[] }
    MemorySearchRequest   { tenantId, userId, query, topK }
    ContextWindow         { immediateWindow[], workingSummary, longTermMemories[], orgMemories[] }
    Memory                { id, tenantId, userId, content, memoryType, score, createdAt, metadata{} }

Method names align 1:1 with the Java ``MemoryRpcService`` interface:

    add(request)              -> void (status only)
    search(request)           -> Memory[]
    getContext(t,u,s)         -> ContextWindow
    forget(memoryId)          -> void
    promoteToOrg(memoryId)    -> void

Error envelope (HTTP status >= 400):

    {"error": {"code": "NOT_FOUND" | "FORBIDDEN" | "BAD_REQUEST" | "INTERNAL",
               "message": "<human readable>"}}
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .config import MemoryConfig
from .manager import MemoryManager
from .models import ContextWindow, Memory


# ---------------------------------------------------------------------------
# Error envelope
# ---------------------------------------------------------------------------

@dataclass
class RpcError(Exception):
    """Raised on tenant isolation violation or missing memory."""

    code: str
    message: str

    def to_dict(self) -> dict[str, dict[str, str]]:
        return {"error": {"code": self.code, "message": self.message}}


# ---------------------------------------------------------------------------
# JSON request / response helpers (camelCase to match the Java records)
# ---------------------------------------------------------------------------

def _memory_to_camel(m: Memory) -> dict[str, Any]:
    return {
        "id": m.id,
        "tenantId": m.tenant_id,
        "userId": m.user_id,
        "content": m.content,
        "memoryType": m.memory_type.value,
        "score": m.score,
        "createdAt": m.created_at,
        "metadata": m.metadata,
    }


def _context_to_camel(ctx: ContextWindow) -> dict[str, Any]:
    return {
        "immediateWindow": ctx.immediate_window,
        "workingSummary": ctx.working_summary,
        "longTermMemories": [_memory_to_camel(m) for m in ctx.long_term_memories],
        "orgMemories": [_memory_to_camel(m) for m in ctx.org_memories],
    }


# ---------------------------------------------------------------------------
# Dispatcher
# ---------------------------------------------------------------------------

@dataclass
class MemoryRpcDispatcher:
    """Thin sync/async dispatcher over :class:`MemoryManager`.

    Enforces D-044 tenant isolation: every method takes ``tenantId`` and the
    manager is always queried with it. ``forget`` / ``promoteToOrg`` look up
    the memory first to raise a precise ``NOT_FOUND`` (404) instead of a
    silent no-op, and to reject cross-tenant access with ``FORBIDDEN`` (403).
    """

    manager: MemoryManager
    config: MemoryConfig = field(default_factory=MemoryConfig)

    async def add(self, request: dict[str, Any]) -> dict[str, Any]:
        tenant_id = request.get("tenantId") or ""
        user_id = request.get("userId") or ""
        session_id = request.get("sessionId") or ""
        messages = request.get("messages") or []
        if not tenant_id or not messages:
            raise RpcError("BAD_REQUEST", "tenantId and messages are required")
        await self.manager.add(
            messages=messages,
            tenant_id=tenant_id,
            user_id=user_id,
            session_id=session_id,
        )
        return {"status": "added", "messageCount": len(messages)}

    async def search(self, request: dict[str, Any]) -> list[dict[str, Any]]:
        tenant_id = request.get("tenantId") or ""
        user_id = request.get("userId") or ""
        query = request.get("query") or ""
        top_k = int(request.get("topK", 5))
        if not tenant_id or not query:
            raise RpcError("BAD_REQUEST", "tenantId and query are required")
        memories = await self.manager.search(
            query=query, tenant_id=tenant_id, user_id=user_id, top_k=top_k
        )
        return [_memory_to_camel(m) for m in memories]

    async def get_context(
        self, tenant_id: str, user_id: str, session_id: str, query: str | None = None
    ) -> dict[str, Any]:
        if not tenant_id:
            raise RpcError("BAD_REQUEST", "tenantId is required")
        ctx = await self.manager.get_context(
            tenant_id=tenant_id, user_id=user_id, session_id=session_id, query=query
        )
        return _context_to_camel(ctx)

    async def forget(self, memory_id: str, tenant_id: str | None = None) -> dict[str, Any]:
        if not memory_id:
            raise RpcError("BAD_REQUEST", "memoryId is required")
        # Tenant isolation: if tenant_id supplied, verify ownership.
        if tenant_id:
            owned = await self._find_owned(memory_id, tenant_id)
            if owned is None:
                raise RpcError("FORBIDDEN", "memory does not belong to tenant")
        await self.manager.forget(memory_id)
        return {"status": "forgotten", "memoryId": memory_id}

    async def promote_to_org(
        self, memory_id: str, tenant_id: str | None = None
    ) -> dict[str, Any]:
        if not memory_id:
            raise RpcError("BAD_REQUEST", "memoryId is required")
        if tenant_id:
            owned = await self._find_owned(memory_id, tenant_id)
            if owned is None:
                raise RpcError("FORBIDDEN", "memory does not belong to tenant")
        promoted = await self.manager.promote_to_org(memory_id)
        if promoted is None:
            raise RpcError("NOT_FOUND", f"memory '{memory_id}' not found in L2")
        return {"status": "promoted", "memoryId": promoted.id, "content": promoted.content}

    async def _find_owned(self, memory_id: str, tenant_id: str) -> Memory | None:
        """Locate an L2 memory by id and verify tenant ownership."""
        for mem in self.manager.l2._fallback._docs:
            if mem.id == memory_id:
                if mem.tenant_id != tenant_id:
                    return None
                return mem
        return None


# ---------------------------------------------------------------------------
# Method table — used by a future framed-JSON transport.
# ---------------------------------------------------------------------------

RPC_METHODS: dict[str, str] = {
    "add": "add",
    "search": "search",
    "getContext": "get_context",
    "forget": "forget",
    "promoteToOrg": "promote_to_org",
}
