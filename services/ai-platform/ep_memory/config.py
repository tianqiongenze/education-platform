"""ep-memory configuration.

Four-level memory architecture configuration (D-045). All settings load from
environment variables.

Iron Law 0 (D-052): In production, backend connection failures raise
``ConnectionError``. Set ``EP_DEV_ONLY=1`` for dev/CI in-memory fallback.

D-053: Neo4j terminated. Procedural memory skill graphs use TiDB recursive
CTE (bounded ≤5-hop), configured via ``tidb_url`` + ``procedural_table``.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class MemoryConfig:
    """Configuration for the multi-level memory system."""

    # Backend connections
    redis_url: str | None = None
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    tidb_url: str | None = None

    # L0 immediate memory
    l0_max_messages: int = 10
    l0_ttl: int = 86400  # 24h

    # L1 working memory
    l1_ttl: int = 604800  # 7d

    # L2 long-term memory
    l2_collection: str = "mem_episodic"
    mem0_enabled: bool = True
    # AI gateway endpoints for Mem0 LLM + embedder (OpenAI-compatible).
    # When unset, Mem0 falls back to its own defaults (which require OPENAI_API_KEY).
    ai_gateway_url: str | None = None
    ai_gateway_llm_model: str = "gpt-4o-mini"
    ai_gateway_embedder_model: str = "text-embedding-3-small"
    ai_gateway_api_key: str | None = None

    # L2 procedural memory — TiDB recursive CTE (D-053: Neo4j→TiDB)
    procedural_table: str = "mem_procedural"
    procedural_max_depth: int = 5  # Max graph traversal depth (bounded ≤5 hops)

    # L3 organizational memory
    l3_collection_prefix: str = "mem_org"

    # Forgetting policy
    l2_decay_days: int = 30
    l2_decay_factor: float = 0.9
    l2_archive_days: int = 90

    @staticmethod
    def load() -> "MemoryConfig":
        """Load configuration from environment variables."""
        redis_url = os.environ.get("REDIS_URL") or None
        return MemoryConfig(
            redis_url=redis_url,
            milvus_host=os.environ.get("MILVUS_HOST", "localhost"),
            milvus_port=int(os.environ.get("MILVUS_PORT", "19530") or 19530),
            tidb_url=os.environ.get("TIDB_URL") or None,
            l0_max_messages=int(os.environ.get("EP_MEM_L0_MAX", "10") or 10),
            l0_ttl=int(os.environ.get("EP_MEM_L0_TTL", "86400") or 86400),
            l1_ttl=int(os.environ.get("EP_MEM_L1_TTL", "604800") or 604800),
            l2_collection=os.environ.get("EP_MEM_L2_COLLECTION", "mem_episodic") or "mem_episodic",
            mem0_enabled=os.environ.get("EP_MEM_MEM0_ENABLED", "1") not in ("0", "false", "False"),
            ai_gateway_url=os.environ.get("EP_AI_GATEWAY_URL") or None,
            ai_gateway_llm_model=os.environ.get("EP_AI_GATEWAY_LLM_MODEL", "gpt-4o-mini")
            or "gpt-4o-mini",
            ai_gateway_embedder_model=os.environ.get(
                "EP_AI_GATEWAY_EMBEDDER_MODEL", "text-embedding-3-small"
            )
            or "text-embedding-3-small",
            ai_gateway_api_key=os.environ.get("EP_AI_GATEWAY_API_KEY") or None,
            procedural_table=os.environ.get("EP_MEM_PROCEDURAL_TABLE", "mem_procedural")
            or "mem_procedural",
            procedural_max_depth=int(os.environ.get("EP_MEM_PROCEDURAL_MAX_DEPTH", "5") or 5),
            l2_decay_days=int(os.environ.get("EP_MEM_L2_DECAY_DAYS", "30") or 30),
            l2_decay_factor=float(os.environ.get("EP_MEM_L2_DECAY_FACTOR", "0.9") or 0.9),
            l2_archive_days=int(os.environ.get("EP_MEM_L2_ARCHIVE_DAYS", "90") or 90),
        )


config = MemoryConfig.load()
