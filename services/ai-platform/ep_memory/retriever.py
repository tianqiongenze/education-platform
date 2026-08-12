"""Memory retriever.

Hybrid retrieval with RRF (Reciprocal Rank Fusion) over vector (Milvus/L2),
BM25 (reserved), and procedural graph (TiDB recursive CTE) sources.

D-053: Neo4j terminated. Graph search now traverses procedural skill graphs
via TiDB recursive CTE (bounded ≤5 hops) — see ``procedural_store.py``.
"""

from __future__ import annotations

import logging

from .l2_longterm import LongTermMemory
from .l3_organizational import OrganizationalMemory
from .models import Memory, MemoryType
from .procedural_store import ProceduralMemory

logger = logging.getLogger("ep_memory.retriever")


def _rrf_fuse(ranked_lists: list[list[Memory]], k: int = 60) -> list[Memory]:
    """Reciprocal Rank Fusion. Aggregates multiple ranked lists into one."""
    scores: dict[str, float] = {}
    memories: dict[str, Memory] = {}
    for ranked in ranked_lists:
        for rank, mem in enumerate(ranked):
            scores[mem.id] = scores.get(mem.id, 0.0) + 1.0 / (k + rank + 1)
            memories[mem.id] = mem
    fused = sorted(memories.values(), key=lambda m: scores[m.id], reverse=True)
    for m in fused:
        m.score = scores[m.id]
    return fused


class MemoryRetriever:
    """Hybrid memory retriever.

    Combines:
      * Vector search (L2 Mem0/Milvus)
      * BM25 keyword search (reserved)
      * Procedural graph search (TiDB recursive CTE for skill graphs, D-053)
      * L3 organizational memory (Milvus, if available)
    """

    def __init__(
        self,
        l2: LongTermMemory,
        l3: OrganizationalMemory | None = None,
        procedural: ProceduralMemory | None = None,
    ) -> None:
        self.l2 = l2
        self.l3 = l3
        self.procedural = procedural

    async def _bm25_search(self, query: str, tenant_id: str, user_id: str, top_k: int) -> list[Memory]:
        """BM25 keyword search hook (reserved). Currently empty."""
        return []

    async def _graph_search(self, query: str, tenant_id: str, user_id: str, top_k: int) -> list[Memory]:
        """Procedural skill graph search via TiDB recursive CTE (D-053).

        Traverses the procedural memory graph from the query (treated as a
        skill name) and returns connected skills as procedural Memory records.
        Returns empty when no procedural store is configured or the query
        doesn't match any known skill.
        """
        if self.procedural is None:
            return []
        try:
            paths = await self.procedural.traverse(tenant_id, query)
            results: list[Memory] = []
            for p in paths[:top_k]:
                results.append(
                    Memory(
                        id=p["id"],
                        tenant_id=tenant_id,
                        content=p["skill_name"],
                        memory_type=MemoryType.PROCEDURAL,
                        score=1.0 / (1.0 + p["depth"]),
                        metadata={"depth": p["depth"], "path": p["path"]},
                    )
                )
            return results
        except ConnectionError:
            # Procedural store unavailable in prod mode — log and skip.
            logger.warning("procedural.search.skipped reason=connection_error")
            return []
        except Exception as exc:  # pragma: no cover - defensive
            logger.warning("procedural.search.error reason=%s", exc)
            return []

    async def retrieve(
        self,
        query: str,
        tenant_id: str,
        user_id: str,
        top_k: int = 5,
        include_org: bool = True,
    ) -> list[Memory]:
        """Retrieve and fuse memories from L2 (and L3 if available)."""
        vector_results = await self.l2.search(query, tenant_id, user_id, top_k=top_k)
        bm25_results = await self._bm25_search(query, tenant_id, user_id, top_k)
        graph_results = await self._graph_search(query, tenant_id, user_id, top_k)

        ranked_lists: list[list[Memory]] = [vector_results, bm25_results, graph_results]
        if include_org and self.l3 is not None:
            org_results = await self.l3.search(query, tenant_id, top_k=top_k)
            ranked_lists.append(org_results)

        fused = _rrf_fuse(ranked_lists)
        return fused[:top_k]
