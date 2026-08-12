"""Pydantic models for the AI gateway."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    model: str | None = None
    temperature: float | None = None
    max_tokens: int | None = None
    stream: bool = False


class ChatResponse(BaseModel):
    id: str
    model: str
    choices: list[dict]
    usage: dict


class EmbeddingRequest(BaseModel):
    input: str | list[str]
    model: str = "text-embedding-3-small"


class EmbeddingResponse(BaseModel):
    model: str
    data: list[dict]
    usage: dict
