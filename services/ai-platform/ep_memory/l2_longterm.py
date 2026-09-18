"""L2 Long-Term Memory.

Delegates extract/retrieve to the official Mem0 pipeline (``mem0ai``). The
Mem0 instance is configured to use:
  * Milvus as the vector store,
  * the AI gateway (OpenAI-compatible) as both the LLM and the embedder.

Iron Law 0 compliance (D-052):
  * Production (EP_DEV_ONLY not set): Mem0/Milvus init failure → ``ConnectionError``.
  * Dev/CI (EP_DEV_ONLY=1): degrades to in-memory backend with a warning.

L0/L1/L3 are intentionally NOT Mem0-dependent.
"""

from __future__ import annotations

import logging
import math
import os
import time
import uuid
from typing import Any

from .config import MemoryConfig
from .extractor import MemoryExtractor
from .models import Memory, MemoryType

logger = logging.getLogger("ep_memory.l2")

try:  # Mem0 is optional; degrade gracefully when not installed.
    from mem0 import Memory as Mem0Memory  # type: ignore

    _HAS_MEM0 = True
except Exception:  # pragma: no cover - exercised only when mem0ai missing
    _HAS_MEM0 = False


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


class _InMemoryBackend:
    """Explicit in-memory fallback backend (NOT a real vector store).

    Used only when Mem0/Milvus is unavailable so the service keeps running
    in development mode. Mirrors the degradation pattern in ai_rag/store.py.
    """

    def __init__(self) -> None:
        self._docs: list[Memory] = []

    def add(self, memory: Memory) -> None:
        self._docs.append(memory)

    def search(
        self,
        query_emb: list[float],
        tenant_id: str,
        user_id: str | None,
        top_k: int,
    ) -> list[Memory]:
        scored: list[tuple[float, Memory]] = []
        for mem in self._docs:
            if mem.tenant_id != tenant_id:
                continue
            if user_id and mem.user_id and mem.user_id != user_id:
                continue
            emb = mem.metadata.get("embedding") or []
            score = _cosine(query_emb, emb)
            mem.score = score
            mem.touch()
            scored.append((score, mem))
        scored.sort(key=lambda t: t[0], reverse=True)
        return [m for s, m in scored[:top_k] if s > 0.0]

    def delete(self, memory_id: str) -> bool:
        before = len(self._docs)
        self._docs = [m for m in self._docs if m.id != memory_id]
        return len(self._docs) < before

    def list_all(self, tenant_id: str, user_id: str | None) -> list[Memory]:
        out: list[Memory] = []
        for m in self._docs:
            if m.tenant_id != tenant_id:
                continue
            if user_id and m.user_id and m.user_id != user_id:
                continue
            out.append(m)
        return out


class _StubEmbedder:
    """Deterministic stub embedder for degraded mode and tests. Produces a
    16-dim vector derived from content hash so identical content yields
    identical embeddings (cosine == 1.0)."""

    DIM = 16

    def embed(self, text: str) -> list[float]:
        vec = [0.0] * self.DIM
        if not text:
            return vec
        for i, ch in enumerate(text):
            vec[i % self.DIM] += (ord(ch) % 97) / 97.0
        # L2 normalize
        norm = math.sqrt(sum(x * x for x in vec)) or 1.0
        return [x / norm for x in vec]


class LongTermMemory:
    """L2: durable memory via Mem0, with in-memory fallback.

    The ``add`` / ``search`` / ``delete`` operations delegate to
    ``mem0.Memory`` when available; otherwise they operate on an explicit
    in-memory backend. The local ``MemoryExtractor`` is still used to produce
    ``Memory`` records returned to callers (Mem0's own LLM extraction is
    invoked in parallel for persistence).
    """

    def __init__(self, config: MemoryConfig) -> None:
        self.config = config
        self.extractor = MemoryExtractor()
        self._mem0: Any = None
        self._fallback = _InMemoryBackend()
        self._embedder = _StubEmbedder()
        if config.mem0_enabled and _HAS_MEM0:
            try:
                mem0_config = self._build_mem0_config()
                self._mem0 = Mem0Memory.from_config(mem0_config)
            except Exception as exc:  # pragma: no cover - env dependent
                if _is_dev_only():
                    logger.warning("mem0.init.fallback reason=%s", exc)
                    self._mem0 = None
                else:
                    logger.error("mem0.init.failed reason=%s", exc)
                    raise ConnectionError(
                        f"Mem0 initialization failed: {exc}. "
                        "Set EP_DEV_ONLY=1 for dev/CI in-memory fallback."
                    ) from exc
        elif config.mem0_enabled and not _HAS_MEM0:
            if _is_dev_only():
                logger.warning("mem0.not_installed dev_only=True")
            else:
                raise ConnectionError(
                    "mem0ai not installed but mem0_enabled=True. "
                    "Install mem0ai or set EP_DEV_ONLY=1 for dev/CI fallback."
                )

    # ------------------------------------------------------------------ #
    # Mem0 configuration
    # ------------------------------------------------------------------ #
    def _build_mem0_config(self) -> dict:
        """Build Mem0 config pointing at Milvus for vectors and the AI
        gateway (OpenAI-compatible) for LLM + embedder.

        When ``ai_gateway_url`` is set, both the LLM and embedder blocks
        use ``openai_base_url`` so Mem0 routes requests through the AI
        gateway instead of calling OpenAI directly. When unset, Mem0 falls
        back to its own defaults (which require OPENAI_API_KEY).
        """
        llm_config: dict[str, Any] = {"model": self.config.ai_gateway_llm_model}
        embedder_config: dict[str, Any] = {
            "model": self.config.ai_gateway_embedder_model
        }
        if self.config.ai_gateway_url:
            llm_config["openai_base_url"] = self.config.ai_gateway_url
            embedder_config["openai_base_url"] = self.config.ai_gateway_url
        if self.config.ai_gateway_api_key:
            llm_config["api_key"] = self.config.ai_gateway_api_key
            embedder_config["api_key"] = self.config.ai_gateway_api_key
        return {
            "vector_store": {
                "provider": "milvus",
                "config": {
                    "host": self.config.milvus_host,
                    "port": self.config.milvus_port,
                    "collection_name": self.config.l2_collection,
                },
            },
            "llm": {"provider": "openai", "config": llm_config},
            "embedder": {"provider": "openai", "config": embedder_config},
        }

    # ------------------------------------------------------------------ #
    # Public API
    # ------------------------------------------------------------------ #
    async def add(self, messages: list[dict], tenant_id: str, user_id: str) -> list[Memory]:
        """Extract memories from messages and persist them.

        Delegates persistence to ``mem0.Memory.add`` (which runs its own LLM
        extraction pipeline) and also returns locally-extracted ``Memory``
        objects so callers can inspect / promote them. When Mem0 is
        unavailable, the local candidates are stored in the in-memory
        fallback backend.
        """
        candidates = self.extractor.extract_memories(
            messages, tenant_id=tenant_id, user_id=user_id
        )
        for mem in candidates:
            mem.metadata["embedding"] = self._embedder.embed(mem.content)

        if self._mem0 is not None:
            try:
                self._mem0.add(
                    messages, user_id=user_id, metadata={"tenant_id": tenant_id}
                )
            except Exception as exc:  # pragma: no cover - env dependent
                logger.warning("mem0.add.fallback reason=%s", exc)
                # Persist locally so the data is not lost.
                for mem in candidates:
                    self._fallback.add(mem)
        else:
            for mem in candidates:
                self._fallback.add(mem)
        return candidates

    async def search(
        self, query: str, tenant_id: str, user_id: str | None = None, top_k: int = 5
    ) -> list[Memory]:
        """Retrieve relevant long-term memories via Mem0.

        Falls back to in-memory cosine search when Mem0/Milvus is
        unavailable.
        """
        if self._mem0 is not None:
            try:
                results = self._mem0.search(query, user_id=user_id, limit=top_k)
                memories: list[Memory] = []
                for r in results:
                    memories.append(
                        Memory(
                            id=r.get("id", f"mem_{uuid.uuid4().hex[:12]}"),
                            tenant_id=tenant_id,
                            user_id=user_id or "",
                            content=r.get("memory", ""),
                            memory_type=MemoryType.EPISODIC,
                            score=float(r.get("score", 1.0)),
                            metadata=r.get("metadata", {}) or {},
                        )
                    )
                return memories
            except Exception as exc:  # pragma: no cover - env dependent
                logger.warning("mem0.search.fallback reason=%s", exc)
        # Fallback: in-memory cosine similarity over stored memories.
        qemb = self._embedder.embed(query)
        return self._fallback.search(qemb, tenant_id, user_id, top_k)

    async def delete(self, memory_id: str) -> bool:
        """Delete a memory by id. Returns True if deleted."""
        deleted_mem0 = False
        if self._mem0 is not None:
            try:
                self._mem0.delete(memory_id=memory_id)
                deleted_mem0 = True
            except Exception as exc:  # pragma: no cover - env dependent
                logger.warning("mem0.delete.fallback reason=%s", exc)
        deleted_local = self._fallback.delete(memory_id)
        return deleted_mem0 or deleted_local

    async def list_all(self, tenant_id: str, user_id: str | None = None) -> list[Memory]:
        return self._fallback.list_all(tenant_id, user_id)
