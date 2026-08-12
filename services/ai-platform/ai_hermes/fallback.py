"""Pulsar-disabled fallback: in-memory asyncio.Queue bus.

When PULSAR_ENABLED=false (default for arm64/cp313 where no wheel exists),
we degrade to an in-memory pub/sub. A WARN log is emitted on initialization
and metrics should alert operators.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

import structlog

from .events import AgentEvent

logger = structlog.get_logger(__name__)


class InMemoryBus:
    """In-memory pub/sub implementing the same interface as PulsarProducer/Consumer."""

    def __init__(self) -> None:
        self._queues: dict[str, list[asyncio.Queue[AgentEvent]]] = {}
        logger.warning("hermes.fallback_mode", reason="PULSAR_ENABLED=false or pulsar-client unavailable")

    async def publish(self, topic: str, event: AgentEvent) -> None:
        for q in self._queues.get(topic, []):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:  # pragma: no cover
                logger.warning("hermes.fallback_queue_full", topic=topic)

    def subscribe(self, topic: str) -> asyncio.Queue[AgentEvent]:
        q: asyncio.Queue[AgentEvent] = asyncio.Queue(maxsize=1000)
        self._queues.setdefault(topic, []).append(q)
        return q

    def unsubscribe(self, topic: str, q: asyncio.Queue[AgentEvent]) -> None:
        subs = self._queues.get(topic, [])
        if q in subs:
            subs.remove(q)


class FallbackProducer:
    def __init__(self, bus: InMemoryBus) -> None:
        self._bus = bus

    async def publish(self, topic: str, event: AgentEvent) -> None:
        await self._bus.publish(topic, event)


class FallbackConsumer:
    def __init__(self, bus: InMemoryBus, topic: str, subscription_name: str) -> None:
        self._bus = bus
        self._topic = topic
        self._sub_name = subscription_name
        self._queue: asyncio.Queue[AgentEvent] | None = None
        self._running = False
        self._max_retries = 3
        self._dead_letters: list[AgentEvent] = []

    async def start(self) -> None:
        self._queue = self._bus.subscribe(self._topic)
        self._running = True

    async def stop(self) -> None:
        self._running = False
        if self._queue is not None:
            self._bus.unsubscribe(self._topic, self._queue)
            self._queue = None

    async def consume(self, handler: Any) -> None:
        if self._queue is None:
            await self.start()
        assert self._queue is not None
        while self._running:
            try:
                event = await asyncio.wait_for(self._queue.get(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            for attempt in range(1, self._max_retries + 1):
                try:
                    await handler(event)
                    break
                except Exception as exc:  # noqa: BLE001
                    logger.warning("hermes.fallback_consume_retry", attempt=attempt, error=str(exc))
                    if attempt == self._max_retries:
                        self._dead_letters.append(event)
                        logger.error("hermes.fallback_dead_letter", event=json.dumps(event.to_dict()))


def is_pulsar_enabled() -> bool:
    import os

    return os.environ.get("PULSAR_ENABLED", "false").lower() == "true"
