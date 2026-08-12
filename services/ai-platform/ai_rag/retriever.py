"""RAG retriever orchestrating embedding, FGAC, and vector store."""

from __future__ import annotations

from .fgac import FGACManager
from .models import Document, IndexRequest, IndexResponse, RetrieveRequest, RetrieveResponse
from .store import VectorStore


class RAGRetriever:
    def __init__(self, store: VectorStore, fgac: FGACManager, embedding_provider, config=None) -> None:
        self.store = store
        self.fgac = fgac
        self.embedding_provider = embedding_provider
        self.config = config

    def _embed(self, text: str) -> list[float]:
        # embedding_provider mirrors MockLLMProvider.embed (returns EmbeddingResponse-like)
        resp = self.embedding_provider.embed_input(text)
        return resp["data"][0]["embedding"]

    async def retrieve(self, request: RetrieveRequest) -> RetrieveResponse:
        query_embedding = self._embed(request.query)
        filter_expr = self.fgac.build_filter_expr(request.tenant_id or "default", request.filters)
        top_k = request.top_k if request.top_k is not None else 5
        docs = await self.store.retrieve(query_embedding, top_k, filter_expr)

        threshold = 0.0
        if self.config is not None and hasattr(self.config, "score_threshold"):
            threshold = self.config.score_threshold
        filtered = [d for d in docs if d.score is None or d.score >= threshold]
        return RetrieveResponse(documents=filtered, query=request.query, total=len(filtered))

    async def index(self, request: IndexRequest) -> IndexResponse:
        errors: list[str] = []
        valid: list[Document] = []
        for doc in request.documents:
            if not doc.id or not doc.content:
                errors.append(f"Invalid document: id={doc.id}")
                continue
            valid.append(doc)
        count = await self.store.index(valid, request.tenant_id)
        return IndexResponse(indexed=count, errors=errors)
