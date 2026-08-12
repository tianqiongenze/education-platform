"""Memory system routes — ep-memory multi-level intelligent memory API.

Endpoints:
    POST   /v1/memory/add          — Add messages to memory (L0→L1→L2)
    GET    /v1/memory/search        — Search long-term memory (L2/L3)
    GET    /v1/memory/context       — Get aggregated context window (L0+L1+L2+L3)
    DELETE /v1/memory/{memory_id}   — Forget (delete) a memory
    POST   /v1/memory/promote       — Promote L2 memory to L3 organizational
    POST   /v1/memory/forgetting    — Trigger a forgetting cycle

D-045 WP4: requests/responses use camelCase to match the Java SDK records
(``com.ep.memory.model.*``). Tenant isolation (D-044) is enforced via
``MemoryRpcDispatcher`` — 404 NOT_FOUND when the memory is absent and 403
FORBIDDEN when it belongs to a different tenant.
"""

from __future__ import annotations

from litestar import Router, delete, get, post
from litestar.exceptions import HTTPException, NotFoundException, PermissionDeniedException
from pydantic import BaseModel

from ep_memory import MemoryConfig, MemoryManager
from ep_memory.rpc import MemoryRpcDispatcher, RpcError


# Singleton manager — in production, backed by Redis + Milvus + TiDB.
_manager: MemoryManager | None = None
_dispatcher: MemoryRpcDispatcher | None = None


def _get_dispatcher() -> MemoryRpcDispatcher:
    global _manager, _dispatcher
    if _dispatcher is None:
        _manager = MemoryManager(MemoryConfig())
        _dispatcher = MemoryRpcDispatcher(manager=_manager, config=MemoryConfig())
    return _dispatcher


def _map_error(e: RpcError) -> HTTPException:
    """Translate RPC errors into HTTP exceptions (404 / 403 / 400)."""
    if e.code == "NOT_FOUND":
        return NotFoundException(detail=e.message)
    if e.code == "FORBIDDEN":
        return PermissionDeniedException(detail=e.message)
    return HTTPException(status_code=400, detail=e.message)


class MemoryAddRequest(BaseModel):
    tenantId: str
    userId: str
    sessionId: str
    messages: list[dict[str, str]]


class MemorySearchRequest(BaseModel):
    query: str
    tenantId: str
    userId: str
    topK: int = 5


class MemoryPromoteRequest(BaseModel):
    memoryId: str
    tenantId: str | None = None  # optional, for tenant isolation check


@post("/v1/memory/add", status_code=201)
async def add_memory(data: MemoryAddRequest) -> dict:
    """Add messages to the memory hierarchy (L0→L1→L2)."""
    disp = _get_dispatcher()
    try:
        return await disp.add(data.model_dump())
    except RpcError as e:
        raise _map_error(e) from e


@post("/v1/memory/search", status_code=200)
async def search_memory(data: MemorySearchRequest) -> dict:
    """Search long-term memory (L2 episodic + L3 organizational).

    Uses POST so the Java SDK can send a JSON body (matching the
    ``MemorySearchRequest`` record) instead of query-string encoding.
    """
    disp = _get_dispatcher()
    try:
        results = await disp.search(data.model_dump())
    except RpcError as e:
        raise _map_error(e) from e
    return {"query": data.query, "results": results}


@get("/v1/memory/search", status_code=200)
async def search_memory_get(
    query: str,
    tenantId: str,
    userId: str,
    topK: int = 5,
) -> dict:
    """GET variant of search for simple curl/REST clients."""
    disp = _get_dispatcher()
    try:
        results = await disp.search(
            {"query": query, "tenantId": tenantId, "userId": userId, "topK": topK}
        )
    except RpcError as e:
        raise _map_error(e) from e
    return {"query": query, "results": results}


@get("/v1/memory/context", status_code=200)
async def get_memory_context(
    tenantId: str,
    userId: str,
    sessionId: str,
    query: str | None = None,
) -> dict:
    """Get the aggregated context window (L0 immediate + L1 working + L2/L3 long-term)."""
    disp = _get_dispatcher()
    try:
        return await disp.get_context(tenantId, userId, sessionId, query)
    except RpcError as e:
        raise _map_error(e) from e


@delete("/v1/memory/{memory_id:str}", status_code=200)
async def forget_memory(memory_id: str, tenantId: str | None = None) -> dict:
    """Delete a memory by ID. ``tenantId`` query param enforces ownership."""
    disp = _get_dispatcher()
    try:
        return await disp.forget(memory_id, tenant_id=tenantId)
    except RpcError as e:
        raise _map_error(e) from e


@post("/v1/memory/promote", status_code=200)
async def promote_memory(data: MemoryPromoteRequest) -> dict:
    """Promote an L2 individual memory to L3 organizational memory."""
    disp = _get_dispatcher()
    try:
        return await disp.promote_to_org(data.memoryId, tenant_id=data.tenantId)
    except RpcError as e:
        raise _map_error(e) from e


@post("/v1/memory/forgetting", status_code=200)
async def run_forgetting_cycle() -> dict:
    """Trigger a forgetting cycle (L2 decay + archival)."""
    manager = _get_dispatcher().manager
    result = await manager.run_forgetting()
    return {"status": "completed", "stats": result}


memory_router = Router(
    path="/",
    route_handlers=[
        add_memory,
        search_memory,
        search_memory_get,
        get_memory_context,
        forget_memory,
        promote_memory,
        run_forgetting_cycle,
    ],
)
