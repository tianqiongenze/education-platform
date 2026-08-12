"""Mock LLM provider — deterministic responses for local dev/testing (Iron Law)."""

from __future__ import annotations

from uuid import uuid4

from .models import ChatRequest, ChatResponse, EmbeddingRequest, EmbeddingResponse


class MockLLMProvider:
    """Deterministic mock LLM provider."""

    def chat(self, request: ChatRequest) -> ChatResponse:
        if not request.messages:
            raise ValueError("messages must not be empty")

        last_user_msg = ""
        for m in reversed(request.messages):
            if m.role == "user":
                last_user_msg = m.content
                break

        if "error" in last_user_msg.lower():
            raise ValueError("Mock error triggered by 'error' keyword in user message")

        prompt_preview = last_user_msg[:50]
        content = f"Mock LLM response for: {prompt_preview}"
        prompt_tokens = sum(len(m.content) // 4 for m in request.messages)
        completion_tokens = 20

        return ChatResponse(
            id=f"mock-{uuid4().hex[:12]}",
            model=request.model or "gpt-4o",
            choices=[
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            usage={
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        )

    def embed(self, request: EmbeddingRequest) -> EmbeddingResponse:
        inputs = request.input if isinstance(request.input, list) else [request.input]
        dim = 1536
        data = []
        for i, _text in enumerate(inputs):
            vec = [0.0] * dim
            vec[0] = 0.1
            data.append({"index": i, "embedding": vec})

        return EmbeddingResponse(
            model=request.model,
            data=data,
            usage={"prompt_tokens": sum(len(t) // 4 for t in inputs), "total_tokens": sum(len(t) // 4 for t in inputs)},
        )
