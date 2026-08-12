"""L3 Organizational Memory.

Tenant-level shared memory stored in Milvus collection ``mem_org_{tenant}``.
Promoted from L2 individual memories.

Iron Law 0 compliance (D-052):
  * Production (EP_DEV_ONLY not set): Milvus connection failure → ``ConnectionError``.
  * Dev/CI (EP_DEV_ONLY=1): degrades to in-memory list with a warning.

D-053 compliance: No Neo4j dependency — procedural memory graph traversal
is handled by TiDB recursive CTE in ``procedural_store.py``, not here.
"""

from __future__ import annotations

import math
import os
import uuid
from typing import Any

import structlog

from .config import MemoryConfig
from .models import Memory, MemoryType

logger = structlog.get_logger(__name__)

try:
    from pymilvus import MilvusClient  # type: ignore

    _HAS_MILVUS = True
except Exception:  # pragma: no cover
    _HAS_MILVUS = False


def _is_dev_only() -> bool:
    """True when running in dev/CI mode (EP_DEV_ONLY=1)."""
    return os.environ.get("EP_DEV_ONLY", "0") in ("1", "true", "True")


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)


class _StubEmbedder:
    """Deterministic stub embedder for dev mode and tests. Produces a
    16-dim vector derived from content hash so identical content yields
    identical embeddings (cosine == 1.0)."""

    DIM = 16

    def embed(self, text: str) -> list[float]:
        vec = [0.0] * self.DIM
        if not text:
            return vec
        for i, ch in enumerate(text):
            vec[i % self.DIM] += (ord(ch) % 97) / 97.0
        norm = math.sqrt(sum(x * x for x in vec)) or 1.0
        return [x / norm for x in vec]


class OrganizationalMemory:
    """L3: tenant-level shared memory backed by Milvus.

    Each tenant gets a dedicated collection ``mem_org_{tenant_id}``. In
    dev/CI mode (``EP_DEV_ONLY=1``) or when pymilvus is not installed,
    falls back to an in-memory list.
    """

    def __init__(self, config: MemoryConfig) -> None:
        self.config = config
        self._embedder = _StubEmbedder()
        self._client: Any = None
        self._connected = False
        # In-memory fallback — ONLY used in dev/CI mode.
        self._fallback: list[Memory] | None = None
        if _is_dev_only():
            self._fallback = []

    def _ensure_connected(self) -> bool:
        """Connect to Milvus on first use. Returns True on success."""
        if self._connected:
            return True
        if self._fallback is not None:
            return False  # Already in dev fallback mode.
        if not _HAS_MILVUS:
            if _is_dev_only():
                logger.warning("ep_memory.l3.no_pymilvus", dev_only=True)
                self._fallback = []
                return False
            raise ConnectionError(
                "pymilvus not installed. Set EP_DEV_ONLY=1 for dev/CI fallback."
            )
        try:
            uri = f"http://{self.config.milvus_host}:{self.config.milvus_port}"
            self._client = MilvusClient(uri=uri)
            self._connected = True
            logger.info("ep_memory.l3.connected", uri=uri)
            return True
        except Exception as exc:
            if _is_dev_only():
                logger.warning("ep_memory.l3.fallback", error=str(exc), dev_only=True)
                self._fallback = []
                return False
            logger.error("ep_memory.l3.connection_failed", error=str(exc))
            raise ConnectionError(
                f"Milvus unreachable at {self.config.milvus_host}:{self.config.milvus_port}: {exc}. "
                "Set EP_DEV_ONLY=1 for dev/CI fallback."
            ) from exc

    def _collection(self, tenant_id: str) -> str:
        # Milvus collection names can only contain numbers, letters, and
        # underscores. Sanitize tenant_id to avoid InvalidCollectionName errors.
        safe_tenant = tenant_id.replace("-", "_").replace(".", "_")
        return f"{self.config.l3_collection_prefix}_{safe_tenant}"

    def _ensure_collection(self, tenant_id: str) -> str:
        """Ensure the tenant's collection exists in Milvus. Returns collection name."""
        collection = self._collection(tenant_id)
        assert self._client is not None
        if not self._client.has_collection(collection):
            from pymilvus import CollectionSchema, FieldSchema, DataType

            fields = [
                FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=128),
                FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535),
                FieldSchema(name="tenant_id", dtype=DataType.VARCHAR, max_length=64),
                FieldSchema(name="user_id", dtype=DataType.VARCHAR, max_length=64),
                FieldSchema(name="memory_type", dtype=DataType.VARCHAR, max_length=32),
                FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=_StubEmbedder.DIM),
            ]
            schema = CollectionSchema(fields=fields, enable_dynamic_field=True)
            self._client.create_collection(collection, schema=schema)
            # Milvus 3.0: create a vector index and load collection into memory
            # before search. Without an index, load_collection fails with
            # "index not found".
            index_params = self._client.prepare_index_params()
            index_params.add_index(
                field_name="embedding",
                index_type="FLAT",
                metric_type="COSINE",
            )
            self._client.create_index(collection, index_params)
            self._client.load_collection(collection)
            logger.info("ep_memory.l3.collection_created", collection=collection)
        return collection

    async def add(self, memory: Memory) -> Memory:
        """Add a memory to the tenant's organizational store."""
        memory.metadata["embedding"] = self._embedder.embed(memory.content)
        memory.metadata["is_org"] = True

        if not self._ensure_connected():
            # Dev fallback path.
            self._fallback.append(memory)  # type: ignore[union-attr]
            return memory

        collection = self._ensure_collection(memory.tenant_id)
        row = {
            "id": memory.id,
            "content": memory.content,
            "tenant_id": memory.tenant_id,
            "user_id": memory.user_id,
            "memory_type": memory.memory_type.value,
            "embedding": memory.metadata["embedding"],
        }
        assert self._client is not None
        self._client.insert(collection, data=[row])
        # Milvus 3.0: flush to make inserted data immediately visible to
        # query/search. Without flush, data sits in the growing segment and
        # is invisible until the next background flush.
        self._client.flush(collection)
        logger.info("ep_memory.l3.added", memory_id=memory.id, tenant=memory.tenant_id)
        return memory

    async def search(self, query: str, tenant_id: str, top_k: int = 5) -> list[Memory]:
        """Search organizational memories by semantic similarity."""
        qemb = self._embedder.embed(query)

        if not self._ensure_connected():
            # Dev fallback: in-memory cosine.
            scored: list[tuple[float, Memory]] = []
            for m in self._fallback or []:  # type: ignore[union-attr]
                if m.tenant_id != tenant_id:
                    continue
                emb = m.metadata.get("embedding") or []
                score = _cosine(qemb, emb)
                m.score = score
                scored.append((score, m))
            scored.sort(key=lambda t: t[0], reverse=True)
            return [m for s, m in scored[:top_k] if s > 0.0]

        collection = self._collection(tenant_id)
        assert self._client is not None
        if not self._client.has_collection(collection):
            return []  # No org memories for this tenant yet.

        results = self._client.search(
            collection_name=collection,
            data=[qemb],
            limit=top_k,
            output_fields=["id", "content", "tenant_id", "user_id", "memory_type"],
        )
        memories: list[Memory] = []
        for hit in (results[0] if results else []):
            entity = hit.get("entity", {}) if isinstance(hit, dict) else {}
            score = hit.get("distance") if isinstance(hit, dict) else getattr(hit, "distance", None)
            mtype_str = entity.get("memory_type", "episodic")
            try:
                mtype = MemoryType(mtype_str)
            except ValueError:
                mtype = MemoryType.EPISODIC
            memories.append(
                Memory(
                    id=entity.get("id", ""),
                    tenant_id=entity.get("tenant_id", tenant_id),
                    user_id=entity.get("user_id", ""),
                    content=entity.get("content", ""),
                    memory_type=mtype,
                    score=float(score) if score is not None else 1.0,
                    metadata={"is_org": True},
                )
            )
        return memories

    async def list_all(self, tenant_id: str) -> list[Memory]:
        """List all organizational memories for a tenant."""
        if not self._ensure_connected():
            return [m for m in (self._fallback or []) if m.tenant_id == tenant_id]  # type: ignore[union-attr]

        collection = self._collection(tenant_id)
        assert self._client is not None
        if not self._client.has_collection(collection):
            return []

        results = self._client.query(
            collection_name=collection,
            filter=f'tenant_id == "{tenant_id}"',
            output_fields=["id", "content", "tenant_id", "user_id", "memory_type"],
            limit=1000,
        )
        memories: list[Memory] = []
        for row in results or []:
            mtype_str = row.get("memory_type", "episodic")
            try:
                mtype = MemoryType(mtype_str)
            except ValueError:
                mtype = MemoryType.EPISODIC
            memories.append(
                Memory(
                    id=row.get("id", ""),
                    tenant_id=row.get("tenant_id", tenant_id),
                    user_id=row.get("user_id", ""),
                    content=row.get("content", ""),
                    memory_type=mtype,
                    metadata={"is_org": True},
                )
            )
        return memories

    async def delete(self, memory_id: str, is_admin: bool = False) -> bool:
        """Delete an org memory. Only admins may delete."""
        if not is_admin:
            raise PermissionError("Only admins can delete organizational memories")

        if not self._ensure_connected():
            before = len(self._fallback or [])  # type: ignore[union-attr]
            self._fallback = [m for m in (self._fallback or []) if m.id != memory_id]  # type: ignore[union-attr]
            return len(self._fallback) < before  # type: ignore[union-attr]

        # Milvus delete by expression filter across tenant collections.
        assert self._client is not None
        collections = self._client.list_collections()
        for col in collections:
            if not col.startswith(self.config.l3_collection_prefix):
                continue
            self._client.delete(col, filter=f'id == "{memory_id}"')
            # Flush to make deletion immediately visible.
            self._client.flush(col)
        return True

    async def promote(self, memory: Memory) -> Memory:
        """Promote an L2 individual memory to L3 organizational memory."""
        org_mem = Memory(
            id=f"org_{uuid.uuid4().hex[:12]}",
            tenant_id=memory.tenant_id,
            user_id="",  # org memories are not user-scoped
            content=memory.content,
            memory_type=memory.memory_type,
            score=memory.score,
            metadata={**memory.metadata, "promoted_from": memory.id},
        )
        return await self.add(org_mem)
