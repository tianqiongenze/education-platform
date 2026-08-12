"""Gateway tests."""

from __future__ import annotations

import pytest
from litestar.testing import TestClient

from ai_gateway.app import create_app
from ai_gateway.mock_llm import MockLLMProvider
from ai_gateway.models import ChatRequest, ChatMessage, EmbeddingRequest


@pytest.fixture()
def client():
    return TestClient(create_app())


def test_health_endpoint(client):
    with client as c:
        resp = c.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "ai-gateway"
    assert body["llm_mode"] == "mock"


def test_chat_completions_mock(client):
    with client as c:
        resp = c.post(
            "/v1/chat/completions",
            json={"messages": [{"role": "user", "content": "Hello there, world!"}]},
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"].startswith("mock-")
    assert body["model"] == "gpt-4o"
    content = body["choices"][0]["message"]["content"]
    assert content.startswith("Mock LLM response for:")
    assert "usage" in body


def test_chat_completions_error(client):
    with client as c:
        resp = c.post(
            "/v1/chat/completions",
            json={"messages": [{"role": "user", "content": "please trigger an error now"}]},
        )
    assert resp.status_code == 400


def test_embeddings_mock(client):
    with client as c:
        resp = c.post("/v1/embeddings", json={"input": "hello world"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["model"] == "text-embedding-3-small"
    assert len(body["data"]) == 1
    vec = body["data"][0]["embedding"]
    assert len(vec) == 1536
    assert vec[0] == pytest.approx(0.1)
    assert vec[1] == pytest.approx(0.0)


def test_embeddings_list_input(client):
    with client as c:
        resp = c.post("/v1/embeddings", json={"input": ["one", "two", "three"]})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["data"]) == 3
    for i, entry in enumerate(body["data"]):
        assert entry["index"] == i
        assert len(entry["embedding"]) == 1536


def test_rate_limit(client):
    # 100 allowed, 101st returns 429
    with client as c:
        for _ in range(100):
            r = c.get("/health")
            assert r.status_code == 200
        r = c.get("/health")
    assert r.status_code == 429


def test_mock_llm_usage_calculation():
    provider = MockLLMProvider()
    req = ChatRequest(messages=[ChatMessage(role="user", content="x" * 40)])
    resp = provider.chat(req)
    # 40 chars // 4 = 10 prompt tokens; completion = 20; total = 30
    assert resp.usage["prompt_tokens"] == 10
    assert resp.usage["completion_tokens"] == 20
    assert resp.usage["total_tokens"] == 30
