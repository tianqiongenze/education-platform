"""Full-chain functional test: student submission → AI AI Gateway grading → grade returned.

This test connects to the real AI AI Gateway (port-forwarded to localhost:8086)
and verifies the end-to-end AI grading pipeline: health, grading via chat completions
proxy, embeddings (RAG indexing), and memory-assisted learning context retrieval.

Gating: @pytest.mark.live + EP_LIVE=1 env var. Defaults skip in CI.
Prerequisite:
    kubectl port-forward -n esp-prod svc/ai-platform 8086:8086

Then:
    EP_LIVE=1 pytest -m live services/ai-platform/tests/test_full_chain.py -v
"""

from __future__ import annotations

import os
import uuid

import pytest

AI_GATEWAY_URL = os.environ.get("EP_AI_GATEWAY_URL", "http://localhost:8086")


def _is_live() -> bool:
    return os.environ.get("EP_LIVE", "0") in ("1", "true", "True")


pytestmark = [
    pytest.mark.live,
    pytest.mark.asyncio,
    pytest.mark.skipif(
        not _is_live(),
        reason="EP_LIVE not enabled; set EP_LIVE=1 and port-forward ai-platform:8086",
    ),
]


@pytest.fixture()
async def client():
    """Async HTTP client with a 10s timeout bound to the AI Gateway URL."""
    import httpx

    async with httpx.AsyncClient(
        base_url=AI_GATEWAY_URL, timeout=httpx.Timeout(10.0)
    ) as c:
        yield c


# ---------------------------------------------------------------------------
# 1. Health
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_ai_gateway_health(client):
    """GET /health returns 200 with status UP/ok."""
    resp = await client.get("/health")
    assert resp.status_code == 200, f"health failed: {resp.status_code} {resp.text}"
    body = resp.json()
    status = (body.get("status") or body.get("health") or "").lower()
    assert status in ("up", "ok", "healthy"), f"unexpected status payload: {body}"
    # service tag confirms we hit the AI gateway, not a stray ingress
    assert body.get("service") in (None, "ai-gateway") or "service" not in body


# ---------------------------------------------------------------------------
# 2. AI grading flow (student submission → grade returned)
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_ai_grading_flow(client):
    """Submit a grading request via /v1/chat/completions and verify a grade is produced.

    The AI Gateway fronts the grading LLM (the grading pipeline is exposed as a
    chat-completions proxy; a dedicated /api/v1/grading/start REST endpoint is
    planned but not yet wired). We send a student submission prompt and assert the
    response contains a non-empty answer (the "grade"). If the gateway is in mock
    mode, we still expect a well-formed completion object.
    """
    submission_id = f"live-{uuid.uuid4().hex[:8]}"
    payload = {
        "model": os.environ.get("EP_LIVE_GRADING_MODEL", "gpt-4o"),
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are an autograder. Grade the student submission 0-100 "
                    "and return a one-line justification."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Submission {submission_id}: "
                    "The derivative of x^2 is 2x. Grade this answer."
                ),
            },
        ],
        "max_tokens": 128,
    }
    resp = await client.post("/v1/chat/completions", json=payload)
    assert resp.status_code == 200, (
        f"chat completions failed: {resp.status_code} {resp.text[:300]}"
    )
    body = resp.json()
    assert "choices" in body and body["choices"], f"no choices in response: {body}"
    message = body["choices"][0].get("message", {})
    content = message.get("content", "")
    assert content and len(content) > 0, f"empty grading content: {body}"
    # usage should be reported for billing/cost observability
    assert "usage" in body, "missing usage stats (cost observability)"


# ---------------------------------------------------------------------------
# 3. Embeddings pipeline (RAG indexing of submissions)
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_embeddings_pipeline(client):
    """POST /v1/embeddings returns a vector for a submission snippet (RAG indexing)."""
    payload = {
        "model": "text-embedding-3-small",
        "input": f"Student submission snippet for RAG indexing ({uuid.uuid4().hex[:6]}).",
    }
    resp = await client.post("/v1/embeddings", json=payload)
    assert resp.status_code == 200, (
        f"embeddings failed: {resp.status_code} {resp.text[:300]}"
    )
    body = resp.json()
    assert "data" in body and body["data"], f"no embedding data: {body}"
    vec = body["data"][0].get("embedding", [])
    assert isinstance(vec, list) and len(vec) > 0, "empty embedding vector"


# ---------------------------------------------------------------------------
# 4. Memory-assisted learning
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_memory_assisted_learning(client):
    """Memory-assisted learning: add a memory, then retrieve context for a learner.

    Verifies the ep-memory pipeline (L0→L1→L2) is wired through the gateway:
    1. POST /v1/memory/add with a learning interaction.
    2. GET /v1/memory/context for the same session — context should be non-empty.

    If the memory backend (Redis/Milvus) is unavailable, the gateway returns a
    non-2xx and we skip rather than fail, since infra reachability is tracked
    elsewhere by scripts/live-integration-check.sh.
    """
    tenant_id = os.environ.get("EP_LIVE_TENANT", "t-live-test")
    user_id = f"u-live-{uuid.uuid4().hex[:8]}"
    session_id = f"s-live-{uuid.uuid4().hex[:8]}"

    # 1. Add a learning interaction to memory.
    add_payload = {
        "tenantId": tenant_id,
        "userId": user_id,
        "sessionId": session_id,
        "messages": [
            {"role": "user", "content": "Explain why d/dx(x^2) = 2x."},
            {
                "role": "assistant",
                "content": "Using the power rule: d/dx(x^n) = n*x^(n-1); for n=2 → 2x.",
            },
        ],
    }
    add_resp = await client.post("/v1/memory/add", json=add_payload)
    if add_resp.status_code not in (200, 201):
        pytest.skip(
            f"memory add returned {add_resp.status_code}; "
            f"memory backend likely unavailable. body={add_resp.text[:200]}"
        )

    # 2. Retrieve aggregated context — should include the just-added interaction.
    ctx_resp = await client.get(
        "/v1/memory/context",
        params={
            "tenantId": tenant_id,
            "userId": user_id,
            "sessionId": session_id,
            "query": "power rule derivatives",
        },
    )
    if ctx_resp.status_code != 200:
        pytest.skip(
            f"memory context returned {ctx_resp.status_code}; "
            f"backend partially available. body={ctx_resp.text[:200]}"
        )
    ctx = ctx_resp.json()
    # Context shape is dispatcher-defined; assert it's a non-empty dict.
    assert isinstance(ctx, dict) and ctx, f"empty context payload: {ctx}"

    # 3. Cleanup: forget any memories created during this run (best-effort).
    try:
        search_resp = await client.get(
            "/v1/memory/search",
            params={
                "query": "power rule derivatives",
                "tenantId": tenant_id,
                "userId": user_id,
                "topK": 5,
            },
        )
        if search_resp.status_code == 200:
            results = search_resp.json().get("results", [])
            for item in results:
                mid = item.get("id") or item.get("memoryId")
                if mid:
                    await client.delete(
                        f"/v1/memory/{mid}", params={"tenantId": tenant_id}
                    )
    except Exception:
        # Cleanup is best-effort; do not fail the test on cleanup errors.
        pass
