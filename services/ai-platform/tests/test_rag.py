"""RAG tests."""

from __future__ import annotations

import os

import pytest

# Force dev/CI mode: use in-memory fallback for MilvusVectorStore so unit tests
# never depend on a live Milvus instance (D-052 Iron Law 0 guard).
# Without this, when pymilvus is installed AND a live Milvus happens to be
# reachable (e.g. via kubectl port-forward), the store would attempt real
# Milvus operations (collection load/search) instead of the in-memory backend
# these tests assert against (e.g. store._fallback._docs).
os.environ["EP_DEV_ONLY"] = "1"

from ai_rag.fgac import FGACManager
from ai_rag.models import Document, IndexRequest, RetrieveRequest
from ai_rag.retriever import RAGRetriever
from ai_rag.store import MilvusVectorStore


class MockEmbeddingProvider:
    def embed_input(self, text: str) -> dict:
        vec = [0.0] * 1536
        vec[0] = 0.1
        return {"data": [{"embedding": vec}]}


def _doc(doc_id, content, tenant_id, score=None, tags=None, embedding=None):
    md = {"tenant_id": tenant_id}
    if tags:
        md["tags"] = tags
    if embedding:
        md["embedding"] = embedding
    return Document(id=doc_id, content=content, metadata=md, score=score)


def test_fgac_register_and_check():
    fgac = FGACManager()
    fgac.register_tenant("t1", ["public", "course_101"])
    assert fgac.check_access("t1", {"tags": ["public"]}) is True
    assert fgac.check_access("t1", {"tags": ["course_101", "secret"]}) is True
    assert fgac.check_access("t1", {"tags": ["secret"]}) is False
    assert fgac.check_access("unknown", {"tags": ["public"]}) is False


def test_fgac_build_filter_expr():
    fgac = FGACManager()
    fgac.register_tenant("t1", ["public", "course_101"])
    expr = fgac.build_filter_expr("t1", {"level": "beginner"})
    assert 'tenant_id == "t1"' in expr
    assert "tag in" in expr
    assert 'level == "beginner"' in expr


@pytest.mark.asyncio
async def test_milvus_store_index_and_retrieve():
    store = MilvusVectorStore()
    emb = [0.1] + [0.0] * 1535
    docs = [_doc("d1", "content one", "t1", embedding=emb)]
    count = await store.index(docs, "t1")
    assert count == 1
    results = await store.retrieve(emb, 5, 'tenant_id == "t1"')
    assert len(results) == 1
    assert results[0].id == "d1"
    assert results[0].score is not None and results[0].score > 0.99


@pytest.mark.asyncio
async def test_milvus_store_tenant_filter():
    store = MilvusVectorStore()
    emb = [0.1] + [0.0] * 1535
    docs = [
        _doc("d1", "a", "t1", embedding=emb),
        _doc("d2", "b", "t2", embedding=emb),
    ]
    await store.index(docs, "t1")  # tenant_id param default; metadata overrides below
    # re-index d2 with t2 metadata explicitly
    store._fallback._docs = []  # type: ignore[union-attr]
    await store.index([_doc("d1", "a", "t1", embedding=emb)], "t1")
    await store.index([_doc("d2", "b", "t2", embedding=emb)], "t2")
    results = await store.retrieve(emb, 5, 'tenant_id == "t1"')
    assert all(d.metadata.get("tenant_id") == "t1" for d in results)
    assert len(results) == 1


@pytest.mark.asyncio
async def test_rag_retriever_with_fgac():
    store = MilvusVectorStore()
    fgac = FGACManager()
    fgac.register_tenant("t1", ["public"])
    emb = [0.1] + [0.0] * 1535
    docs = [
        _doc("d1", "content one", "t1", tags=["public"], embedding=emb),
        _doc("d2", "content two", "t2", tags=["public"], embedding=emb),
    ]
    await store.index([docs[0]], "t1")
    await store.index([docs[1]], "t2")

    retriever = RAGRetriever(store, fgac, MockEmbeddingProvider(), config=None)
    resp = await retriever.retrieve(RetrieveRequest(query="hello", tenant_id="t1"))
    assert resp.query == "hello"
    assert all(d.metadata.get("tenant_id") == "t1" for d in resp.documents)


@pytest.mark.asyncio
async def test_rag_retriever_score_threshold():
    store = MilvusVectorStore()
    fgac = FGACManager()
    fgac.register_tenant("t1", ["public"])

    # high-similarity doc and orthogonal doc
    emb_match = [0.1] + [0.0] * 1535
    emb_orth = [0.0, 0.1] + [0.0] * 1534
    query_emb = [0.1] + [0.0] * 1535

    # The mock embedding provider returns emb_match-style vectors, so query embedding == emb_match
    docs = [
        _doc("d1", "match", "t1", tags=["public"], embedding=emb_match),
        _doc("d2", "orth", "t1", tags=["public"], embedding=emb_orth),
    ]
    await store.index(docs, "t1")

    class ThresholdCfg:
        score_threshold = 0.7

    retriever = RAGRetriever(store, fgac, MockEmbeddingProvider(), config=ThresholdCfg())
    resp = await retriever.retrieve(RetrieveRequest(query="hello", tenant_id="t1"))
    ids = {d.id for d in resp.documents}
    assert "d1" in ids
    assert "d2" not in ids  # orthogonal vector has ~0 cosine similarity


@pytest.mark.asyncio
async def test_rag_index_request():
    store = MilvusVectorStore()
    fgac = FGACManager()
    fgac.register_tenant("t1", ["public"])
    retriever = RAGRetriever(store, fgac, MockEmbeddingProvider(), config=None)
    docs = [
        Document(id="d1", content="c1", metadata={}),
        Document(id="d2", content="c2", metadata={}),
        Document(id="", content="bad", metadata={}),
    ]
    resp = await retriever.index(IndexRequest(documents=docs, tenant_id="t1"))
    assert resp.indexed == 2
    assert len(resp.errors) == 1
