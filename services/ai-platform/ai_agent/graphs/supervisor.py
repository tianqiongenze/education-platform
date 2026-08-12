"""Top-level Supervisor: intent router.

Analyzes the last user message and classifies intent into one of the
industry-specific agent domains (loaded from ``IndustryConfig.intent_keywords``)
or the core ``qa_agent``, then routes the parent graph accordingly.

The keyword map is config-driven (see ``ai_agent/industry_config.py`` and
``config/education.yaml``). The platform code itself is industry-agnostic.
"""

from __future__ import annotations

import os

from ..industry_config import IndustryConfig, load_industry_config
from ..state import AgentState

# Lazily-loaded industry config. ``_config`` is read by ``_classify``; it is
# refreshed by calling ``_load_config()``. We avoid loading at import time so
# that test environments can set ``INDUSTRY_CONFIG_PATH`` / ``EP_DEV_ONLY``
# before the supervisor is used.
_config: IndustryConfig | None = None


def _load_config() -> IndustryConfig:
    """Load (and cache) the industry config.

    The path comes from ``INDUSTRY_CONFIG_PATH`` if set, otherwise the loader's
    default (``config/education.yaml``).
    """
    global _config
    if _config is None:
        path = os.environ.get("INDUSTRY_CONFIG_PATH")
        _config = load_industry_config(path)
    return _config


def _set_config(cfg: IndustryConfig | None) -> None:
    """Test hook: override the cached config (pass None to force reload)."""
    global _config
    _config = cfg


def _classify(message: str) -> str:
    """Classify a user message to an agent name via config-driven keywords.

    When the config has no ``intent_keywords`` (e.g. generic industry), all
    messages default to ``qa_agent``.
    """
    cfg = _load_config()
    keywords_map = cfg.intent_keywords or {}
    if not keywords_map:
        # Generic industry: route everything to the core QA agent.
        return "qa_agent"
    lowered = message.lower()
    for agent, keywords in keywords_map.items():
        for kw in keywords:
            if kw in lowered:
                return agent
    # default to QA for general questions
    return "qa_agent"


def supervisor_node(state: AgentState) -> dict:
    """Route to a sub-agent based on the last user message.

    Acts as a state machine:
    - On first invocation (no ``_round_done`` flag) it classifies intent and
      selects a sub-agent.
    - On re-entry after a subgraph has run (``_round_done`` is True) it emits
      ``current_agent="END"`` so ``supervisor_route`` terminates the parent
      graph. This guarantees a single subgraph invocation per run rather than
      an infinite supervisor<->subgraph loop.
    """
    if state.get("_round_done"):
        return {"current_agent": "END", "current_node": "supervisor"}
    message = ""
    for m in reversed(state.get("messages", [])):
        if m.get("role") == "user":
            message = m.get("content", "")
            break
    agent = _classify(message)
    return {
        "current_agent": agent,
        "current_node": "supervisor",
        "_round_done": True,
    }


def supervisor_route(state: AgentState) -> str:
    """Conditional edge: read current_agent and emit the next node name."""
    agent = state.get("current_agent")
    # Valid set is built dynamically from the industry config's registered
    # agents plus the core ``qa_agent`` and ``END``. Unknown agents (e.g. when
    # a subgraph failed to register) fall through to END for graceful
    # degradation.
    cfg = _load_config()
    valid: set[str] = {a.name for a in cfg.agent_graphs}
    valid.add("qa_agent")
    valid.add("END")
    if agent in valid:
        return agent  # type: ignore[return-value]
    return "END"
