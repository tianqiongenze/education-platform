"""RAG Pydantic models."""

from __future__ import annotations

from pydantic import BaseModel


class Document(BaseModel):
    id: str
    content: str
    metadata: dict
    score: float | None = None


class RetrieveRequest(BaseModel):
    query: str
    top_k: int | None = None
    filters: dict | None = None
    tenant_id: str | None = None


class RetrieveResponse(BaseModel):
    documents: list[Document]
    query: str
    total: int


class IndexRequest(BaseModel):
    documents: list[Document]
    tenant_id: str


class IndexResponse(BaseModel):
    indexed: int
    errors: list[str]
