"""TokenBudget — per-tenant×agent×model token and cost budgeting.

Integrates with Redis for cross-instance budget tracking (D-006 session externalization).
Falls back to in-memory when Redis is unavailable (D-033 cache fallback).
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

import structlog

logger = structlog.get_logger(__name__)


@dataclass
class TokenBudget:
    """Token and cost budget for a tenant×agent×model triple.

    Stored in Redis as: budget:{tenant}:{agent}:{model}
    Value: JSON({consumed_tokens, consumed_cost_cents, window_start, window_size_sec})
    """

    tenant_id: str
    agent: str
    model: str
    max_tokens: int = 100000
    max_cost_cents: int = 500  # $5.00
    window_size_sec: int = 86400  # 24h rolling window
    consumed_tokens: int = 0
    consumed_cost_cents: int = 0
    window_start: float = field(default_factory=time.time)
    _redis: Any = None  # redis.asyncio.Redis, injected externally

    @property
    def redis_key(self) -> str:
        return f"budget:{self.tenant_id}:{self.agent}:{self.model}"

    def _check_window_reset(self) -> None:
        """Reset budget if window has elapsed."""
        now = time.time()
        if now - self.window_start > self.window_size_sec:
            self.consumed_tokens = 0
            self.consumed_cost_cents = 0
            self.window_start = now

    def can_spend(self, tokens: int, cost_cents: int = 0) -> bool:
        """Check if budget allows spending the given tokens and cost."""
        self._check_window_reset()
        return (
            self.consumed_tokens + tokens <= self.max_tokens
            and self.consumed_cost_cents + cost_cents <= self.max_cost_cents
        )

    def spend(self, tokens: int, cost_cents: int = 0) -> None:
        """Record token and cost consumption. Raises BudgetExceeded if over limit."""
        self._check_window_reset()
        if not self.can_spend(tokens, cost_cents):
            raise BudgetExceeded(
                f"Budget exceeded for {self.tenant_id}:{self.agent}:{self.model}: "
                f"tokens {self.consumed_tokens}/{self.max_tokens}, "
                f"cost {self.consumed_cost_cents}/{self.max_cost_cents} cents"
            )
        self.consumed_tokens += tokens
        self.consumed_cost_cents += cost_cents
        logger.debug(
            "budget_spend",
            tenant=self.tenant_id,
            agent=self.agent,
            model=self.model,
            tokens=self.consumed_tokens,
            max_tokens=self.max_tokens,
            cost_cents=self.consumed_cost_cents,
            max_cost_cents=self.max_cost_cents,
        )

    async def sync_from_redis(self) -> None:
        """Sync budget state from Redis (if available)."""
        if not self._redis:
            return
        try:
            import json

            raw = await self._redis.get(self.redis_key)
            if raw:
                data = json.loads(raw)
                self.consumed_tokens = data.get("consumed_tokens", 0)
                self.consumed_cost_cents = data.get("consumed_cost_cents", 0)
                self.window_start = data.get("window_start", time.time())
        except Exception as e:
            logger.warning("budget_sync_failed", error=str(e), key=self.redis_key)

    async def persist_to_redis(self) -> None:
        """Persist budget state to Redis (if available)."""
        if not self._redis:
            return
        try:
            import json

            data = {
                "consumed_tokens": self.consumed_tokens,
                "consumed_cost_cents": self.consumed_cost_cents,
                "window_start": self.window_start,
            }
            await self._redis.set(self.redis_key, json.dumps(data), ex=self.window_size_sec)
        except Exception as e:
            logger.warning("budget_persist_failed", error=str(e), key=self.redis_key)

    def to_dict(self) -> dict[str, Any]:
        """Serialize for API responses."""
        self._check_window_reset()
        return {
            "tenant_id": self.tenant_id,
            "agent": self.agent,
            "model": self.model,
            "max_tokens": self.max_tokens,
            "consumed_tokens": self.consumed_tokens,
            "max_cost_cents": self.max_cost_cents,
            "consumed_cost_cents": self.consumed_cost_cents,
            "remaining_tokens": max(0, self.max_tokens - self.consumed_tokens),
            "remaining_cost_cents": max(0, self.max_cost_cents - self.consumed_cost_cents),
        }


class BudgetExceeded(Exception):
    """Raised when token or cost budget is exceeded."""

    pass
