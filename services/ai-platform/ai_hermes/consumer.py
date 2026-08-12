"""Pulsar consumer wrapper using built-in retry + dead-letter policy.

Relies on Pulsar's native ``DeadLetterPolicy`` (max redeliveries + dead-letter
topic) instead of a hand-written retry/DLQ loop. The consumer simply
acknowledges on success and lets Pulsar handle redelivery; after
``max_retries`` failed deliveries Pulsar automatically routes the message to
the configured dead-letter topic.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, Callable

import structlog

from .events import AgentEvent

logger = structlog.get_logger(__name__)


class PulsarConsumer:
    """Async Pulsar consumer backed by Pulsar's native DeadLetterPolicy.

    Parameters
    ----------
    max_retries:
        Maps to ``DeadLetterPolicy.max_redeliver_count`` — the number of
        times Pulsar will redeliver a message before routing it to the DLQ.
    dead_letter_topic:
        The DLQ topic. Defaults to ``<topic>-dlq``.
    """

    def __init__(
        self,
        pulsar_url: str,
        topic: str,
        subscription_name: str,
        *,
        max_retries: int = 3,
        dead_letter_topic: str | None = None,
    ) -> None:
        self._url = pulsar_url
        self._topic = topic
        self._sub_name = subscription_name
        self._max_retries = max_retries
        self._dlq_topic = dead_letter_topic or f"{topic}-dlq"
        self._client: Any | None = None
        self._consumer: Any | None = None
        self._running = False
        # Kept only for backwards-compatible inspection in tests/observability.
        # The authoritative DLQ is the Pulsar dead-letter topic.
        self._dead_letters: list[AgentEvent] = []

    async def start(self) -> None:
        try:
            import pulsar  # type: ignore[import-untyped]

            self._client = pulsar.Client(self._url)
            dl_policy = (
                pulsar.DeadLetterPolicyBuilder()
                .max_redeliver_count(self._max_retries)
                .dead_letter_topic(self._dlq_topic)
                .build()
            )
            self._consumer = self._client.subscribe(
                self._topic,
                subscription_name=self._sub_name,
                consumer_type=pulsar.ConsumerType.Shared,
                dead_letter_policy=dl_policy,
            )
            self._running = True
            logger.info(
                "hermes.consumer_started",
                topic=self._topic,
                sub=self._sub_name,
                dlq=self._dlq_topic,
                max_retries=self._max_retries,
            )
        except Exception as exc:  # noqa: BLE001
            logger.error("hermes.consumer_start_failed", error=str(exc))
            raise

    async def stop(self) -> None:
        self._running = False
        if self._consumer is not None:
            try:
                self._consumer.close()
            except Exception:  # noqa: BLE001
                pass
        if self._client is not None:
            try:
                self._client.close()
            except Exception:  # noqa: BLE001
                pass

    async def consume(self, handler: Callable[[AgentEvent], Any]) -> None:
        """Consume messages, delegating retry/DLQ to Pulsar.

        On handler failure we ``negative_acknowledge`` the message so Pulsar
        redelivers it. After ``max_retries`` redeliveries Pulsar's
        ``DeadLetterPolicy`` routes it to the dead-letter topic automatically —
        no hand-written retry loop or local DLQ list required.
        """
        if self._consumer is None:
            await self.start()
        assert self._consumer is not None
        loop = asyncio.get_running_loop()

        def _try_receive() -> Any:
            return self._consumer.receive(timeout_millis=1000)

        while self._running:
            try:
                msg = await loop.run_in_executor(None, _try_receive)
            except Exception:  # noqa: BLE001
                # timeout or transient; loop again
                continue
            try:
                data = json.loads(msg.data().decode("utf-8"))
                event = AgentEvent.from_dict(data)
            except Exception as exc:  # noqa: BLE001
                logger.error("hermes.decode_failed", error=str(exc))
                self._consumer.acknowledge(msg)
                continue

            try:
                result = handler(event)
                if asyncio.iscoroutine(result):
                    await result
                self._consumer.acknowledge(msg)
            except Exception as exc:  # noqa: BLE001
                # Let Pulsar handle redelivery + DLQ via DeadLetterPolicy.
                logger.warning(
                    "hermes.consume_failed",
                    error=str(exc),
                    dlq=self._dlq_topic,
                )
                # Track locally for observability; authoritative DLQ is Pulsar.
                self._dead_letters.append(event)
                try:
                    self._consumer.negative_acknowledge(msg)
                except Exception:  # noqa: BLE001
                    self._consumer.acknowledge(msg)

    @property
    def dead_letters(self) -> list[AgentEvent]:
        """Locally observed failures (observability only). The authoritative
        dead-letter queue is the Pulsar ``dead_letter_topic``."""
        return list(self._dead_letters)
