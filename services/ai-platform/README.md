# SP-04 AI Middle-Platform

AI middle-platform for the education-platform project (D-019).

## Modules

- **ai_gateway** — LLM Gateway (Litestar + Granian). Exposes `/v1/chat/completions` and `/v1/embeddings`. Supports `LLM_MODE=mock` for deterministic local responses.
- **ai_rag** — Retrieval Augmented Generation with Milvus/pgvector backends and FGAC (fine-grained access control) per tenant.
- **ai_agent** — LangGraph-based agent with retrieval, reasoning, and response nodes; in-memory checkpointer for MVP.

## Iron Law: Mock Mode

`LLM_MODE=mock` (default) — all LLM calls return deterministic mock responses. No external API calls are made.

## Run Tests

```bash
cd services/sp04-ai
python -m pytest tests/ -v --tb=short
```

## Run Gateway (dev)

```bash
python -m granian --interface asgi ai_gateway.app:create_app
```
