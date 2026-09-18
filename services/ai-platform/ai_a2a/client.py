"""A2A client backed by the official ``a2a-sdk`` (v0.3.x, pydantic models).

Protocol semantics (transport, framing, task/message lifecycle) are delegated
to ``a2a.client.A2AClient``. The public ``A2AClient`` surface
(``send_task``/``get_task``/``cancel_task``/``subscribe_task``/
``send_notification``) is preserved as a compatibility shim that converts
between the local :class:`~ai_a2a.task.Task` dataclasses and the SDK's
pydantic types.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, AsyncIterator

import httpx
import structlog

from .message import Message
from .task import Task, TaskStatus

logger = structlog.get_logger(__name__)


def _import_a2a_client_factory() -> Any:
    """Lazy import of the SDK client factory."""
    from a2a.client import ClientFactory

    return ClientFactory


def _import_a2a_types() -> Any:
    """Lazy import of the SDK pydantic types module."""
    from a2a import types

    return types


# --- mapping between local TaskStatus and SDK TaskState ---------------------

_STATUS_TO_STATE: dict[TaskStatus, str] = {
    TaskStatus.SUBMITTED: "submitted",
    TaskStatus.WORKING: "working",
    TaskStatus.INPUT_REQUIRED: "input_required",
    TaskStatus.COMPLETED: "completed",
    TaskStatus.CANCELED: "canceled",
    TaskStatus.FAILED: "failed",
    TaskStatus.REJECTED: "rejected",
}

_STATE_TO_STATUS: dict[str, TaskStatus] = {v: k for k, v in _STATUS_TO_STATE.items()}


def _sdk_task_to_local(sdk_task: Any) -> Task:
    """Convert an a2a-sdk pydantic ``Task`` to the local ``Task`` dataclass."""
    state_name = getattr(sdk_task.status.state, "value", str(sdk_task.status.state))
    status = _STATE_TO_STATUS.get(state_name, TaskStatus.SUBMITTED)

    message = None
    if sdk_task.status.message:
        message = Message.from_dict(sdk_task.status.message.model_dump())

    from .message import Artifact

    artifacts = []
    for a in (sdk_task.artifacts or []):
        artifacts.append(Artifact.from_dict(a.model_dump()))

    return Task(
        id=sdk_task.id,
        status=status,
        message=message,
        artifacts=artifacts,
    )


def _local_message_to_sdk(local_msg: Message) -> Any:
    """Convert a local ``Message`` dataclass to an a2a-sdk pydantic ``Message``."""
    types = _import_a2a_types()
    from .message import DataPart, FilePart, TextPart

    role = types.Role.agent if local_msg.role == "agent" else types.Role.user

    parts: list[Any] = []
    for part in local_msg.parts:
        if isinstance(part, TextPart):
            parts.append(types.Part(root=types.TextPart(text=part.text)))
        elif isinstance(part, FilePart):
            file_payload: dict[str, Any] = {}
            if part.mime_type:
                file_payload["mimeType"] = part.mime_type
            if part.bytes_b64:
                file_payload["bytesWithTrailingData"] = part.bytes_b64
            if part.uri:
                file_payload["uri"] = part.uri
            parts.append(types.Part(root=types.FilePart(**file_payload)))
        elif isinstance(part, DataPart):
            parts.append(types.Part(root=types.DataPart(data=part.json_data)))

    return types.Message(
        messageId=uuid.uuid4().hex,
        role=role,
        parts=parts or [types.Part(root=types.TextPart(text=""))],
    )


class A2AClient:
    """Async A2A client delegating protocol handling to ``a2a-sdk``."""

    def __init__(self, http_client: httpx.AsyncClient | None = None, *, timeout: float = 30.0) -> None:
        # Keep the injected client for tests that stub the transport; the SDK
        # client is created lazily per agent URL so we don't need to resolve
        # an AgentCard at construction time.
        self._client = http_client or httpx.AsyncClient(timeout=timeout)
        self._timeout = timeout
        self._sdk_clients: dict[str, Any] = {}

    async def __aenter__(self) -> "A2AClient":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        for sdk in list(self._sdk_clients.values()):
            close = getattr(sdk, "close", None) or getattr(sdk, "aclose", None)
            if close is not None:
                try:
                    res = close()
                    if asyncio.iscoroutine(res):
                        await res
                except Exception:  # noqa: BLE001
                    pass
        self._sdk_clients.clear()
        close = getattr(self._client, "aclose", None)
        if close is not None:
            await close()

    async def _get_sdk_client(self, agent_url: str) -> Any:
        """Resolve (and cache) an a2a-sdk ``Client`` for ``agent_url``."""
        if agent_url in self._sdk_clients:
            return self._sdk_clients[agent_url]
        factory = _import_a2a_client_factory()()
        # ``create_from_url`` fetches /.well-known/agent.json and builds a
        # Client wired to the right transport. It accepts resolver_http_kwargs
        # so we can reuse an existing httpx client for auth/timeouts.
        sdk_client = await factory.create_from_url(
            agent_url.rstrip("/"),
            resolver_http_kwargs={"timeout": self._timeout},
        )
        self._sdk_clients[agent_url] = sdk_client
        return sdk_client

    async def send_task(self, agent_url: str, task: Task) -> Task:
        """Send a task/message to a remote agent and return the resulting Task."""
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        local_msg = task.message or Message(role="user", parts=[])
        sdk_msg = _local_message_to_sdk(local_msg)
        req = types.SendMessageRequest(
            id=uuid.uuid4().hex,
            params=types.MessageSendParams(message=sdk_msg),
        )
        resp = await sdk.send_message(req)
        # SendMessageResponse.root is a union: JSONRPCErrorResponse | SendMessageSuccessResponse
        root = resp.root
        if isinstance(root, types.SendMessageSuccessResponse):
            result = root.result
            if isinstance(result, types.Task):
                return _sdk_task_to_local(result)
        # Error or message-only response — return a submitted task
        return Task(id=task.id, status=TaskStatus.SUBMITTED, tenant_id=task.tenant_id)

    async def get_task(self, agent_url: str, task_id: str) -> Task:
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        req = types.GetTaskRequest(
            id=uuid.uuid4().hex,
            params=types.TaskQueryParams(id=task_id),
        )
        resp = await sdk.get_task(req)
        root = resp.root
        if isinstance(root, types.GetTaskSuccessResponse):
            return _sdk_task_to_local(root.result)
        return Task(id=task_id, status=TaskStatus.SUBMITTED)

    async def cancel_task(self, agent_url: str, task_id: str) -> Task:
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        req = types.CancelTaskRequest(
            id=uuid.uuid4().hex,
            params=types.TaskIdParams(id=task_id),
        )
        resp = await sdk.cancel_task(req)
        root = resp.root
        if isinstance(root, types.CancelTaskSuccessResponse):
            return _sdk_task_to_local(root.result)
        return Task(id=task_id, status=TaskStatus.CANCELED)

    async def subscribe_task(self, agent_url: str, task_id: str) -> AsyncIterator[Task]:
        """Stream task updates via the SDK's ``resubscribe`` method."""
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        req = types.TaskResubscriptionRequest(
            id=uuid.uuid4().hex,
            params=types.TaskIdParams(id=task_id),
        )
        async for event in sdk.resubscribe(req):
            root = event.root
            if isinstance(root, types.SendStreamingMessageSuccessResponse):
                result = root.result
                if isinstance(result, types.Task):
                    yield _sdk_task_to_local(result)

    async def send_notification(self, webhook_url: str, task: Task) -> None:
        """POST a task status update to a registered webhook.

        Webhooks are an out-of-band notification channel, not part of the
        A2A JSON-RPC protocol, so they remain plain HTTP POSTs.
        """
        payload = {"task": task.to_dict()}
        try:
            resp = await self._client.post(webhook_url, json=payload)
            resp.raise_for_status()
        except Exception as exc:  # noqa: BLE001
            logger.warning("a2a.notification_failed", webhook=webhook_url, error=str(exc))
            raise
