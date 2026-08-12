"""Hermes EventBus - unified publish/subscribe API.

Delegates to PulsarProducer/PulsarConsumer when PULSAR_ENABLED=true,
otherwise falls back to in-memory asyncio.Queue (fallback.py).
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, Awaitable, Callable

import structlog

from .events import AgentEvent
from .fallback import FallbackConsumer, FallbackProducer, InMemoryBus, is_pulsar_enabled

logger = structlog.get_logger(__name__)

DEFAULT_TOPIC = "persistent://public/hermes/events"

Handler = Callable[[AgentEvent], Awaitable[None] | None]


class EventBus:
    """High-level event bus. Subscriptions are keyed by event_type and routed to topics."""

    def __init__(self, pulsar_url: str | None = None, *, enabled: bool | None = None) -> None:
        self._pulsar_url = pulsar_url
        self._enabled = enabled if enabled is not None else is_pulsar_enabled()
        self._subscriptions: dict[str, str] = {}  # subscription_id -> event_type
        self._handlers: dict[str, Handler] = {}
        self._mem_bus: InMemoryBus | None = None
        self._mem_producer: FallbackProducer | None = None
        self._mem_consumers: list[FallbackConsumer] = []
        self._pulsar_producer: Any | None = None
        self._pulsar_consumers: list[Any] = []
        self._tasks: list[asyncio.Task[None]] = []

        if not self._enabled:
            self._mem_bus = InMemoryBus()
            self._mem_producer = FallbackProducer(self._mem_bus)

    @property
    def enabled(self) -> bool:
        return self._enabled

    async def publish(self, event: AgentEvent, *, topic: str = DEFAULT_TOPIC) -> None:
        if self._enabled:
            if self._pulsar_producer is None:
                from .producer import PulsarProducer

                self._pulsar_producer = PulsarProducer(self._pulsar_url or "pulsar://localhost:6650")
            await self._pulsar_producer.publish(topic, event)
        else:
            assert self._mem_producer is not None
            await self._mem_producer.publish(topic, event)
        logger.debug("hermes.publish", event_type=event.event_type, topic=topic)

    async def subscribe(self, event_type: str, handler: Handler, *, topic: str | None = None) -> str:
        sub_id = uuid.uuid4().hex
        self._subscriptions[sub_id] = event_type
        self._handlers[sub_id] = handler

        target_topic = topic or DEFAULT_TOPIC

        async def _wrapper(event: AgentEvent) -> None:
            if sub_id not in self._subscriptions:
                return
            if event.event_type == event_type:
                result = handler(event)
                if asyncio.iscoroutine(result):
                    await result

        if self._enabled:
            from .consumer import PulsarConsumer

            consumer = PulsarConsumer(
                self._pulsar_url or "pulsar://localhost:6650",
                target_topic,
                f"hermes-{event_type}-{sub_id[:8]}",
            )
            self._pulsar_consumers.append(consumer)
            task = asyncio.create_task(consumer.consume(_wrapper))
            self._tasks.append(task)
        else:
            assert self._mem_bus is not None
            consumer = FallbackConsumer(self._mem_bus, target_topic, f"hermes-{event_type}-{sub_id[:8]}")
            await consumer.start()
            self._mem_consumers.append(consumer)
            task = asyncio.create_task(consumer.consume(_wrapper))
            self._tasks.append(task)

        logger.info("hermes.subscribe", event_type=event_type, sub_id=sub_id)
        return sub_id

    def unsubscribe(self, subscription_id: str) -> None:
        self._subscriptions.pop(subscription_id, None)
        self._handlers.pop(subscription_id, None)
        # Stop matching fallback consumers so no further events fire
        for c in list(self._mem_consumers):
            if subscription_id in c._sub_name:  # type: ignore[attr-defined]
                asyncio.ensure_future(c.stop())
                self._mem_consumers.remove(c)
        for c in list(self._pulsar_consumers):
            if subscription_id in c._sub_name:  # type: ignore[attr-defined]
                asyncio.ensure_future(c.stop())
                self._pulsar_consumers.remove(c)
        logger.info("hermes.unsubscribe", sub_id=subscription_id)

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        for c in self._pulsar_consumers:
            await c.stop()
        for c in self._mem_consumers:
            await c.stop()
        if self._pulsar_producer is not None:
            await self._pulsar_producer.close()
        self._tasks.clear()
        self._pulsar_consumers.clear()
        self._mem_consumers.clear()

    @staticmethod
    def _topic_for_event(event_type: str) -> str:
        return f"persistent://public/hermes/{event_type.lower().replace('_', '-')}"
