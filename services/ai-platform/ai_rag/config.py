"""RAG configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class Settings:
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    milvus_collection: str = "ep_knowledge"
    pg_dsn: str = "postgresql://rag:rag@localhost:5432/rag"
    embedding_dim: int = 1536
    top_k: int = 5
    score_threshold: float = 0.7

    @staticmethod
    def load() -> "Settings":
        return Settings(
            milvus_host=os.environ.get("MILVUS_HOST", "localhost"),
            milvus_port=int(os.environ.get("MILVUS_PORT", "19530")),
            milvus_collection=os.environ.get("MILVUS_COLLECTION", "ep_knowledge"),
            pg_dsn=os.environ.get("PG_DSN", "postgresql://rag:rag@localhost:5432/rag"),
            embedding_dim=int(os.environ.get("EMBEDDING_DIM", "1536")),
            top_k=int(os.environ.get("RAG_TOP_K", "5")),
            score_threshold=float(os.environ.get("RAG_SCORE_THRESHOLD", "0.7")),
        )


config = Settings.load()
