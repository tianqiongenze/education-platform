"""Gateway configuration loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class Settings:
    llm_mode: str = "mock"
    llm_api_url: str | None = None
    llm_api_key: str | None = None
    llm_model: str = "gpt-4o"
    llm_timeout_sec: int = 30
    max_tokens: int = 4096
    temperature: float = 0.7
    pulsar_url: str | None = None
    pulsar_topic_llm_request: str = "persistent://public/default/llm-request"
    pulsar_topic_llm_response: str = "persistent://public/default/llm-response"

    @staticmethod
    def load() -> "Settings":
        def _get(name: str, default=None):
            val = os.environ.get(name)
            return val if val is not None else default

        return Settings(
            llm_mode=_get("LLM_MODE", "mock") or "mock",
            llm_api_url=_get("LLM_API_URL"),
            llm_api_key=_get("LLM_API_KEY"),
            llm_model=_get("LLM_MODEL", "gpt-4o") or "gpt-4o",
            llm_timeout_sec=int(_get("LLM_TIMEOUT_SEC", "30") or 30),
            max_tokens=int(_get("MAX_TOKENS", "4096") or 4096),
            temperature=float(_get("TEMPERATURE", "0.7") or 0.7),
            pulsar_url=_get("PULSAR_URL"),
            pulsar_topic_llm_request=_get("PULSAR_TOPIC_LLM_REQUEST", "persistent://public/default/llm-request")
            or "persistent://public/default/llm-request",
            pulsar_topic_llm_response=_get("PULSAR_TOPIC_LLM_RESPONSE", "persistent://public/default/llm-response")
            or "persistent://public/default/llm-response",
        )


config = Settings.load()
