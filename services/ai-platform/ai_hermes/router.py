"""Event router - rule-based routing with optional LLM fallback."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

import structlog

from .events import AgentEvent

logger = structlog.get_logger(__name__)

ROUTES_REDIS_KEY = "hermes:routes"


@dataclass
class RouteRule:
    event_type: str
    targets: list[str] = field(default_factory=list)  # agent names or topics

    def to_dict(self) -> dict[str, Any]:
        return {"event_type": self.event_type, "targets": list(self.targets)}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "RouteRule":
        return cls(event_type=data["event_type"], targets=list(data.get("targets", [])))


def _build_default_rules() -> dict[str, RouteRule]:
    """Build the default rule table from the industry config's ``event_routes``.

    This keeps the platform industry-agnostic: education routes live in
    ``config/education.yaml``, not in source code. When the config has no
    routes (e.g. generic industry), the table is empty and the LLM fallback
    (if any) handles unmatched events.
    """
    try:
        from ai_agent.industry_config import load_industry_config

        cfg = load_industry_config()
    except Exception as exc:  # noqa: BLE001 — never block the bus on config load
        logger.warning("router.config_load_failed", error=str(exc))
        return {}

    rules: dict[str, RouteRule] = {}
    for event_type, targets in (cfg.event_routes or {}).items():
        rules[event_type] = RouteRule(event_type=event_type, targets=list(targets))
    return rules


# Default rule table (low cost, deterministic), loaded from industry config.
_DEFAULT_RULES: dict[str, RouteRule] = _build_default_rules()


class EventRouter:
    """Routes events to target agents/topics.

    Rule-based routing first (Redis hot-reload key=hermes:routes);
    small LLM fallback for complex/unmatched events.
    """

    def __init__(self, redis_client: Any | None = None, *, llm_router: Any | None = None) -> None:
        self._redis = redis_client
        self._llm_router = llm_router
        self._rules: dict[str, RouteRule] = dict(_DEFAULT_RULES)

    async def _load_hot_rules(self) -> None:
        if self._redis is None:
            return
        try:
            raw = await self._redis.get(ROUTES_REDIS_KEY)
            if raw:
                data = json.loads(raw)
                for entry in data.get("rules", []):
                    rule = RouteRule.from_dict(entry)
                    self._rules[rule.event_type] = rule
        except Exception as exc:  # noqa: BLE001
            logger.warning("router.hot_reload_failed", error=str(exc))

    async def route(self, event: AgentEvent) -> list[str]:
        await self._load_hot_rules()
        rule = self._rules.get(event.event_type)
        if rule and rule.targets:
            return list(rule.targets)
        # LLM fallback for unmatched events
        if self._llm_router is not None:
            try:
                result = await self._llm_router.route(event)
                if isinstance(result, list):
                    return result
            except Exception as exc:  # noqa: BLE001
                logger.warning("router.llm_fallback_failed", error=str(exc))
        logger.info("router.no_match", event_type=event.event_type)
        return []

    def set_rule(self, event_type: str, targets: list[str]) -> None:
        self._rules[event_type] = RouteRule(event_type, list(targets))

    async def update_rule_redis(self, event_type: str, targets: list[str]) -> None:
        self._rules[event_type] = RouteRule(event_type, list(targets))
        if self._redis is not None:
            payload = {"rules": [r.to_dict() for r in self._rules.values()]}
            await self._redis.set(ROUTES_REDIS_KEY, json.dumps(payload))
