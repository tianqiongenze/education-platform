"""Vector store implementations backed by pymilvus and pgvector.

Connection semantics (Iron Law 0 — no silent failures):
  * **Production** (``EP_DEV_ONLY`` unset/0): if Milvus/PG is unreachable the
    store raises ``ConnectionError`` immediately. No silent fallback.
  * **Dev/CI** (``EP_DEV_ONLY=1``): falls back to an in-memory backend and
    logs a warning, so tests run without infrastructure.

This replaces the previous unconditional fallback that violated Iron Law 0 —
all production code paths silently degraded to in-memory, masking real
outages (D-052).
"""

from __future__ import annotations

import math
import os
import uuid
from abc import ABC, abstractmethod
from typing import Any

import structlog

from .config import Settings
from .models import Document

logger = structlog.get_logger(__name__)


def _is_dev_only() -> bool:
    """True when running in dev/CI mode (EP_DEV_ONLY=1)."""
    return os.environ.get("EP_DEV_ONLY", "0") in ("1", "true", "True")


# --- shared in-memory fallback ---------------------------------------------


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)


class _InMemoryBackend:
    """Minimal in-memory vector backend used when no DB is reachable."""

    def __init__(self) -> None:
        self._docs: list[Document] = []

    async def index(self, documents: list[Document], tenant_id: str) -> int:
        count = 0
        for doc in documents:
            md = dict(doc.metadata)
            md.setdefault("tenant_id", tenant_id)
            self._docs.append(
                Document(id=doc.id, content=doc.content, metadata=md, score=doc.score)
            )
            count += 1
        return count

    async def retrieve(
        self, query_embedding: list[float], top_k: int, filter_expr: str
    ) -> list[Document]:
        tenant_id = _extract_tenant(filter_expr)
        scored: list[tuple[float, Document]] = []
        for doc in self._docs:
            if tenant_id and doc.metadata.get("tenant_id") != tenant_id:
                continue
            emb = doc.metadata.get("embedding")
            if not emb:
                continue
            score = _cosine(query_embedding, emb)
            scored.append((score, doc))
        scored.sort(key=lambda t: t[0], reverse=True)
        results = []
        for score, doc in scored[:top_k]:
            results.append(
                Document(id=doc.id, content=doc.content, metadata=doc.metadata, score=score)
            )
        return results


def _extract_tenant(filter_expr: str) -> str | None:
    """Extract tenant_id value from a filter expression like 'tenant_id == "t1" && ...'"""
    if not filter_expr:
        return None
    marker = 'tenant_id == "'
    idx = filter_expr.find(marker)
    if idx < 0:
        return None
    start = idx + len(marker)
    end = filter_expr.find('"', start)
    if end < 0:
        return None
    return filter_expr[start:end]


class VectorStore(ABC):
    @abstractmethod
    async def retrieve(self, query_embedding: list[float], top_k: int, filter_expr: str) -> list[Document]:
        ...

    @abstractmethod
    async def index(self, documents: list[Document], tenant_id: str) -> int:
        ...


# --- Milvus (pymilvus 2.4+) ------------------------------------------------


class MilvusVectorStore(VectorStore):
    """Vector store backed by Milvus via ``pymilvus``.

    Iron Law 0 compliance:
      * Production (EP_DEV_ONLY not set): connection failure → ``ConnectionError``.
      * Dev/CI (EP_DEV_ONLY=1): falls back to in-memory backend with a warning.
    """

    def __init__(self, config: Settings | None = None) -> None:
        self.config = config or Settings.load()
        self._client: Any = None
        self._connected = False
        self._fallback: _InMemoryBackend | None = None

    def _ensure_connected(self) -> bool:
        """Try to connect to Milvus once. Returns True on success.

        Iron Law 0 — dev/CI (``EP_DEV_ONLY=1``) never touches real Milvus, even
        when a port-forward happens to be reachable on the configured host/port.
        This keeps unit tests deterministic and free of live infrastructure
        side effects (e.g. leftover collections from ``kubectl port-forward``).
        """
        if self._connected:
            return True
        if self._fallback is not None:
            return False
        if _is_dev_only():
            logger.warning("rag.milvus.fallback", dev_only=True, reason="EP_DEV_ONLY=1")
            self._fallback = _InMemoryBackend()
            return False
        try:
            from pymilvus import MilvusClient

            uri = f"http://{self.config.milvus_host}:{self.config.milvus_port}"
            self._client = MilvusClient(uri=uri)
            self._connected = True
            logger.info("rag.milvus.connected", uri=uri)
            return True
        except Exception as exc:  # noqa: BLE001
            # Production: fail loud — no silent degradation.
            logger.error("rag.milvus.connection_failed", error=str(exc))
            raise ConnectionError(
                f"Milvus unreachable at {self.config.milvus_host}:{self.config.milvus_port}: {exc}. "
                "Set EP_DEV_ONLY=1 for dev/CI fallback."
            ) from exc

    async def index(self, documents: list[Document], tenant_id: str) -> int:
        if not self._ensure_connected():
            return await self._fallback.index(documents, tenant_id)  # type: ignore[union-attr]
        return await self._index_milvus(documents, tenant_id)

    async def _index_milvus(self, documents: list[Document], tenant_id: str) -> int:
        """Insert documents into a Milvus collection."""
        dim = self.config.embedding_dim
        collection = self.config.milvus_collection
        # Ensure collection exists with the right schema.
        from pymilvus import CollectionSchema, FieldSchema, DataType

        if not self._client.has_collection(collection):  # type: ignore[union-attr]
            fields = [
                FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=128),
                FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535),
                FieldSchema(name="tenant_id", dtype=DataType.VARCHAR, max_length=64),
                FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim),
            ]
            schema = CollectionSchema(fields=fields, enable_dynamic_field=True)
            self._client.create_collection(collection, schema=schema)  # type: ignore[union-attr]

        rows = []
        count = 0
        for doc in documents:
            if not doc.id:
                doc.id = uuid.uuid4().hex
            emb = doc.metadata.get("embedding") or [0.0] * dim
            rows.append(
                {
                    "id": doc.id,
                    "content": doc.content,
                    "tenant_id": tenant_id,
                    "embedding": emb,
                }
            )
            count += 1
        if rows:
            self._client.insert(collection, data=rows)  # type: ignore[union-attr]
        return count

    async def retrieve(
        self, query_embedding: list[float], top_k: int, filter_expr: str
    ) -> list[Document]:
        if not self._ensure_connected():
            return await self._fallback.retrieve(query_embedding, top_k, filter_expr)  # type: ignore[union-attr]
        collection = self.config.milvus_collection
        results = self._client.search(  # type: ignore[union-attr]
            collection_name=collection,
            data=[query_embedding],
            limit=top_k,
            filter=filter_expr or None,
            output_fields=["id", "content", "tenant_id"],
        )
        docs: list[Document] = []
        # ``search`` returns a list of hits per query vector.
        for hit in (results[0] if results else []):
            entity = hit.get("entity", {}) if isinstance(hit, dict) else {}
            score = hit.get("distance") if isinstance(hit, dict) else getattr(hit, "distance", None)
            docs.append(
                Document(
                    id=entity.get("id", ""),
                    content=entity.get("content", ""),
                    metadata={"tenant_id": entity.get("tenant_id")},
                    score=score,
                )
            )
        return docs


# --- PostgreSQL (pgvector) -------------------------------------------------


class PostgresVectorStore(VectorStore):
    """Vector store backed by PostgreSQL + pgvector via ``psycopg``.

    Iron Law 0 compliance:
      * Production (EP_DEV_ONLY not set): connection failure → ``ConnectionError``.
      * Dev/CI (EP_DEV_ONLY=1): falls back to in-memory backend with a warning.
    """

    def __init__(self, config: Settings | None = None) -> None:
        self.config = config or Settings.load()
        self._conn: Any = None
        self._connected = False
        self._fallback: _InMemoryBackend | None = None

    def _ensure_connected(self) -> bool:
        if self._connected:
            return True
        if self._fallback is not None:
            return False
        try:
            import psycopg

            self._conn = psycopg.connect(self.config.pg_dsn, autocommit=True)
            # Ensure pgvector extension and table exist.
            self._conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
            self._conn.execute(
                """
                CREATE TABLE IF NOT EXISTS ep_documents (
                    id TEXT PRIMARY KEY,
                    content TEXT,
                    tenant_id TEXT,
                    embedding vector(%s)
                )
                """,
                (self.config.embedding_dim,),
            )
            self._connected = True
            logger.info("rag.pgvector.connected", dsn=self.config.pg_dsn)
            return True
        except Exception as exc:  # noqa: BLE001
            if _is_dev_only():
                logger.warning("rag.pgvector.fallback", error=str(exc), dev_only=True)
                self._fallback = _InMemoryBackend()
                return False
            logger.error("rag.pgvector.connection_failed", error=str(exc))
            raise ConnectionError(
                f"PostgreSQL unreachable at {self.config.pg_dsn}: {exc}. "
                "Set EP_DEV_ONLY=1 for dev/CI fallback."
            ) from exc

    async def index(self, documents: list[Document], tenant_id: str) -> int:
        if not self._ensure_connected():
            return await self._fallback.index(documents, tenant_id)  # type: ignore[union-attr]
        count = 0
        for doc in documents:
            if not doc.id:
                doc.id = uuid.uuid4().hex
            emb = doc.metadata.get("embedding") or [0.0] * self.config.embedding_dim
            emb_str = "[" + ",".join(str(x) for x in emb) + "]"
            self._conn.execute(  # type: ignore[union-attr]
                "INSERT INTO ep_documents (id, content, tenant_id, embedding) "
                "VALUES (%s, %s, %s, %s) ON CONFLICT (id) DO UPDATE SET "
                "content=EXCLUDED.content, tenant_id=EXCLUDED.tenant_id, embedding=EXCLUDED.embedding",
                (doc.id, doc.content, tenant_id, emb_str),
            )
            count += 1
        return count

    async def retrieve(
        self, query_embedding: list[float], top_k: int, filter_expr: str
    ) -> list[Document]:
        if not self._ensure_connected():
            return await self._fallback.retrieve(query_embedding, top_k, filter_expr)  # type: ignore[union-attr]
        tenant_id = _extract_tenant(filter_expr)
        emb_str = "[" + ",".join(str(x) for x in query_embedding) + "]"
        sql = (
            "SELECT id, content, tenant_id, embedding <=> %s::vector AS distance "
            "FROM ep_documents"
        )
        params: list[Any] = [emb_str]
        if tenant_id:
            sql += " WHERE tenant_id = %s"
            params.append(tenant_id)
        sql += " ORDER BY embedding <=> %s::vector LIMIT %s"
        params.extend([emb_str, top_k])
        cur = self._conn.execute(sql, params)  # type: ignore[union-attr]
        docs: list[Document] = []
        for row in cur.fetchall():
            docs.append(
                Document(
                    id=row[0],
                    content=row[1],
                    metadata={"tenant_id": row[2]},
                    score=1.0 - float(row[3]) if row[3] is not None else None,
                )
            )
        return docs
