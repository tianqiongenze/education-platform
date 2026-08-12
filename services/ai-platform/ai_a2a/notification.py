"""A2A push notifications via Hermes event bus + webhook callbacks."""

from __future__ import annotations

from typing import Any

import structlog

from .task import Task

logger = structlog.get_logger(__name__)


class NotificationManager:
    """Manages task status-change notifications.

    - Publishes to Pulsar topic persistent://public/a2a/task-events via Hermes EventBus.
    - Also fires registered webhooks.
    """

    WEBHOOK_TOPIC = "persistent://public/a2a/task-events"

    def __init__(self, event_bus: Any | None = None, a2a_client: Any | None = None) -> None:
        self._bus = event_bus
        self._client = a2a_client
        self._webhooks: dict[str, str] = {}  # task_id -> webhook_url

    def register_webhook(self, task_id: str, webhook_url: str) -> None:
        self._webhooks[task_id] = webhook_url
        logger.info("notification.webhook_registered", task_id=task_id)

    def unregister_webhook(self, task_id: str) -> None:
        self._webhooks.pop(task_id, None)

    async def notify(self, task: Task) -> None:
        """Notify subscribers of a task status change."""
        # 1. Hermes event
        if self._bus is not None:
            try:
                from ..ai_hermes.events import AgentEvent
                event = AgentEvent(
                    event_type="TASK_STATUS_CHANGED",
                    tenant_id=task.tenant_id,
                    task_id=task.id,
                    payload={"status": task.status.value, "task": task.to_dict()},
                )
                await self._bus.publish(event, topic=self.WEBHOOK_TOPIC)
            except Exception as exc:  # noqa: BLE001
                logger.warning("notification.bus_publish_failed", error=str(exc))

        # 2. Webhook
        webhook = self._webhooks.get(task.id)
        if webhook and self._client is not None:
            try:
                await self._client.send_notification(webhook, task)
            except Exception as exc:  # noqa: BLE001
                logger.warning("notification.webhook_failed", task_id=task.id, error=str(exc))
