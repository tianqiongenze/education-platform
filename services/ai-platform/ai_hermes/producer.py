"""Pulsar producer wrapper."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import structlog

from .events import AgentEvent

logger = structlog.get_logger(__name__)


class PulsarProducer:
    """Async wrapper over pulsar-client Producer.

    Connection pool keyed by topic; batch send with async ack.
    """

    def __init__(self, pulsar_url: str) -> None:
        self._url = pulsar_url
        self._client: Any | None = None
        self._producers: dict[str, Any] = {}
        self._lock = asyncio.Lock()
        self._connected = False

    async def connect(self) -> None:
        if self._connected:
            return
        try:
            import pulsar  # type: ignore[import-untyped]

            self._client = pulsar.Client(self._url)
            self._connected = True
            logger.info("hermes.producer_connected", url=self._url)
        except Exception as exc:  # noqa: BLE001
            logger.error("hermes.producer_connect_failed", error=str(exc))
            raise

    async def _get_producer(self, topic: str) -> Any:
        if topic in self._producers:
            return self._producers[topic]
        async with self._lock:
            if topic in self._producers:
                return self._producers[topic]
            assert self._client is not None
            producer = self._client.create_producer(topic)
            self._producers[topic] = producer
            return producer

    async def publish(self, topic: str, event: AgentEvent) -> None:
        if not self._connected:
            await self.connect()
        producer = await self._get_producer(topic)
        payload = json.dumps(event.to_dict(), ensure_ascii=False).encode("utf-8")
        # pulsar-client is sync; run in executor to avoid blocking the loop
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, producer.send, payload)
        logger.debug("hermes.published", topic=topic, trace_id=event.trace_id)

    async def close(self) -> None:
        for producer in self._producers.values():
            try:
                producer.close()
            except Exception:  # noqa: BLE001
                pass
        if self._client is not None:
            try:
                self._client.close()
            except Exception:  # noqa: BLE001
                pass
        self._connected = False
