"""Agent registry with Redis cache + local YAML fallback (D-033)."""

from __future__ import annotations

import json
import os
from typing import Any

import structlog

from .agent_card import AgentCard

logger = structlog.get_logger(__name__)

_REGISTRY_FILE_ENV = "A2A_REGISTRY_YAML"


class AgentRegistry:
    """Registry of known agent cards.

    Redis key: a2a:agents:{tenant}. Local YAML fallback for offline/dev.
    """

    REDIS_PREFIX = "a2a:agents"

    def __init__(self, redis_client: Any | None = None, yaml_path: str | None = None) -> None:
        self._redis = redis_client
        self._yaml_path = yaml_path or os.environ.get(_REGISTRY_FILE_ENV)
        self._local: dict[str, AgentCard] = {}
        self._load_yaml()

    def _load_yaml(self) -> None:
        if not self._yaml_path or not os.path.exists(self._yaml_path):
            return
        try:
            import yaml  # type: ignore[import-untyped]
        except ImportError:
            logger.warning("registry.yaml_no_pyyaml")
            return
        try:
            with open(self._yaml_path, "r", encoding="utf-8") as fh:
                data = yaml.safe_load(fh) or {}
            for entry in data.get("agents", []):
                card = AgentCard.from_dict(entry)
                self._local[card.name] = card
            logger.info("registry.yaml_loaded", count=len(self._local))
        except Exception as exc:  # noqa: BLE001
            logger.error("registry.yaml_load_failed", error=str(exc))

    def _key(self, tenant_id: str) -> str:
        return f"{self.REDIS_PREFIX}:{tenant_id}"

    async def register(self, card: AgentCard) -> None:
        self._local[card.name] = card
        if self._redis is not None:
            tenant = card.tenant_id or "default"
            raw = await self._redis.get(self._key(tenant))
            store: dict[str, Any] = json.loads(raw) if raw else {}
            store[card.name] = card.to_dict()
            await self._redis.set(self._key(tenant), json.dumps(store))
        logger.info("registry.registered", agent=card.name)

    async def unregister(self, agent_name: str, tenant_id: str | None = None) -> bool:
        existed = agent_name in self._local
        self._local.pop(agent_name, None)
        if self._redis is not None and tenant_id is not None:
            raw = await self._redis.get(self._key(tenant_id))
            if raw:
                store = json.loads(raw)
                existed = store.pop(agent_name, None) is not None or existed
                await self._redis.set(self._key(tenant_id), json.dumps(store))
        if existed:
            logger.info("registry.unregistered", agent=agent_name)
        return existed

    async def get_agent(self, agent_name: str) -> AgentCard | None:
        if agent_name in self._local:
            return self._local[agent_name]
        if self._redis is not None:
            # Best-effort: search across tenants is not feasible without index.
            # Caller should pass tenant via list_agents; here we just scan default.
            raw = await self._redis.get(self._key("default"))
            if raw:
                store = json.loads(raw)
                data = store.get(agent_name)
                if data:
                    card = AgentCard.from_dict(data)
                    self._local[agent_name] = card
                    return card
        return None

    async def list_agents(self, tenant_id: str | None = None) -> list[AgentCard]:
        tenant = tenant_id or "default"
        if self._redis is not None:
            raw = await self._redis.get(self._key(tenant))
            if raw:
                store = json.loads(raw)
                return [AgentCard.from_dict(v) for v in store.values()]
        # Local fallback: filter by tenant if specified
        cards = list(self._local.values())
        if tenant_id is not None:
            cards = [c for c in cards if c.tenant_id in (None, tenant_id)]
        return cards

    async def discover(self, agent_url: str, *, http_client: Any | None = None) -> AgentCard | None:
        """Fetch /.well-known/agent.json from a known endpoint."""
        url = agent_url.rstrip("/")
        well_known = f"{url}/.well-known/agent.json"
        try:
            if http_client is not None:
                resp = await http_client.get(well_known)
                data = resp.json()
            else:
                import httpx

                async with httpx.AsyncClient(timeout=5.0) as client:
                    resp = await client.get(well_known)
                    resp.raise_for_status()
                    data = resp.json()
            card = AgentCard.from_dict(data)
            await self.register(card)
            logger.info("registry.discovered", agent=card.name, url=agent_url)
            return card
        except Exception as exc:  # noqa: BLE001
            logger.warning("registry.discover_failed", url=agent_url, error=str(exc))
            return None
