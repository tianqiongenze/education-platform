"""Agent configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class Settings:
    graph_checkpoint_backend: str = "memory"
    redis_url: str | None = None
    redis_checkpointer_url: str | None = None
    pulsar_url: str | None = None
    max_iterations: int = 10
    agent_model: str = "gpt-4o"
    system_prompt: str = "You are an AI assistant."
    # LangGraph version pin (informational)
    langgraph_version: str = "^1.0"
    # Industry configuration YAML path (None → use default education.yaml).
    industry_config_path: str | None = None
    # Reflexion settings
    reflexion_max_trials: int = 3
    reflexion_threshold: float = 0.7
    # Feature toggles
    mcp_enabled: bool = False
    a2a_enabled: bool = False
    hermes_enabled: bool = False
    # D-045 multi-level memory integration
    ep_memory_enabled: bool = True
    ep_memory_top_k: int = 5

    @staticmethod
    def load() -> "Settings":
        def _env(key: str, default: str) -> str:
            return os.environ.get(key, default) or default

        def _bool(key: str, default: bool) -> bool:
            return os.environ.get(key, str(default)).lower() in {"1", "true", "yes"}

        return Settings(
            graph_checkpoint_backend=_env("GRAPH_CHECKPOINT_BACKEND", "memory"),
            redis_url=os.environ.get("REDIS_URL"),
            redis_checkpointer_url=os.environ.get("REDIS_CHECKPOINTER_URL") or os.environ.get("REDIS_URL"),
            pulsar_url=os.environ.get("PULSAR_URL"),
            max_iterations=int(_env("AGENT_MAX_ITERATIONS", "10")),
            agent_model=_env("AGENT_MODEL", "gpt-4o"),
            system_prompt=_env("AGENT_SYSTEM_PROMPT", "You are an AI assistant."),
            langgraph_version=_env("LANGGRAPH_VERSION", "^1.0"),
            reflexion_max_trials=int(_env("REFLEXION_MAX_TRIALS", "3")),
            reflexion_threshold=float(_env("REFLEXION_THRESHOLD", "0.7")),
            mcp_enabled=_bool("MCP_ENABLED", False),
            a2a_enabled=_bool("A2A_ENABLED", False),
            hermes_enabled=_bool("HERMES_ENABLED", False),
            ep_memory_enabled=_bool("EP_MEMORY_ENABLED", True),
            ep_memory_top_k=int(_env("EP_MEMORY_TOP_K", "5")),
            industry_config_path=os.environ.get("INDUSTRY_CONFIG_PATH"),
        )


config = Settings.load()
