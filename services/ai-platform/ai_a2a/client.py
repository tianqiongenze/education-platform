"""A2A client backed by the official ``a2a-sdk``.

The hand-written JSON-RPC framing is gone. Protocol semantics (transport,
framing, task/message lifecycle) are delegated to ``a2a.client.Client``.
The public ``A2AClient`` surface (``send_task``/``get_task``/``cancel_task``/
``subscribe_task``/``send_notification``) is preserved as a compatibility
shim that converts between the local :class:`~ai_a2a.task.Task` dataclasses
and the SDK's protobuf types.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, AsyncIterator

import httpx
import structlog
from google.protobuf.json_format import MessageToDict, ParseDict

from .message import Message
from .task import Task, TaskStatus

logger = structlog.get_logger(__name__)


def _import_a2a_client_factory() -> Any:
    """Lazy import of the SDK client factory."""
    from a2a.client import ClientFactory

    return ClientFactory


def _import_a2a_types() -> Any:
    """Lazy import of the SDK protobuf types module."""
    from a2a import types

    return types


# --- mapping between local TaskStatus and SDK TaskState ---------------------

_STATUS_TO_STATE: dict[TaskStatus, str] = {
    TaskStatus.SUBMITTED: "TASK_STATE_SUBMITTED",
    TaskStatus.WORKING: "TASK_STATE_WORKING",
    TaskStatus.INPUT_REQUIRED: "TASK_STATE_INPUT_REQUIRED",
    TaskStatus.COMPLETED: "TASK_STATE_COMPLETED",
    TaskStatus.CANCELED: "TASK_STATE_CANCELED",
    TaskStatus.FAILED: "TASK_STATE_FAILED",
    TaskStatus.REJECTED: "TASK_STATE_REJECTED",
}

_STATE_TO_STATUS: dict[str, TaskStatus] = {v: k for k, v in _STATUS_TO_STATE.items()}


def _sdk_task_to_local(sdk_task: Any) -> Task:
    """Convert an a2a-sdk protobuf ``Task`` to the local ``Task`` dataclass."""
    d = MessageToDict(sdk_task, preserving_proto_field_name=True)
    state_name = d.get("status", {}).get("state", "TASK_STATE_SUBMITTED")
    status = _STATE_TO_STATUS.get(state_name, TaskStatus.SUBMITTED)
    message = None
    if d.get("status", {}).get("message"):
        message = Message.from_dict(d["status"]["message"])
    from .message import Artifact

    artifacts = [Artifact.from_dict(a) for a in d.get("artifacts", [])]
    error = d.get("status", {}).get("message", {}).get("metadata", {}).get("error")
    return Task(
        id=d.get("id", uuid.uuid4().hex),
        status=status,
        message=message,
        artifacts=artifacts,
        error=error,
    )


def _local_message_to_sdk(local_msg: Message) -> Any:
    """Convert a local ``Message`` dataclass to an a2a-sdk ``Message``."""
    types = _import_a2a_types()
    from .message import DataPart, FilePart, TextPart

    role = "ROLE_AGENT" if local_msg.role == "agent" else "ROLE_USER"
    parts_pb: list[dict[str, Any]] = []
    for part in local_msg.parts:
        if isinstance(part, TextPart):
            parts_pb.append({"text": part.text})
        elif isinstance(part, FilePart):
            file_payload: dict[str, Any] = {}
            if part.mime_type:
                file_payload["mimeType"] = part.mime_type
            if part.bytes_b64:
                file_payload["bytesWithTrailingData"] = part.bytes_b64
            if part.uri:
                file_payload["uri"] = part.uri
            parts_pb.append({"file": file_payload})
        elif isinstance(part, DataPart):
            parts_pb.append({"data": part.json_data})
    msg_dict = {
        "messageId": uuid.uuid4().hex,
        "role": role,
        "parts": parts_pb or [{"text": ""}],
    }
    return ParseDict(msg_dict, types.Message())


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
            tenant=task.tenant_id or "",
            message=sdk_msg,
        )
        # ``send_message`` is an async iterator over StreamResponse events.
        final_task: Task | None = None
        async for event in sdk.send_message(req):
            kind = event.WhichOneof("payload") if hasattr(event, "WhichOneof") else None
            if kind == "task":
                final_task = _sdk_task_to_local(event.task)
            elif kind == "status_update":
                state_name = (
                    MessageToDict(event.status_update, preserving_proto_field_name=True)
                    .get("status", {})
                    .get("state", "TASK_STATE_WORKING")
                )
                status = _STATE_TO_STATUS.get(state_name, TaskStatus.WORKING)
                final_task = Task(id=task.id, status=status, tenant_id=task.tenant_id)
            # artifact-only updates don't replace the task; ignore here.
        if final_task is None:
            final_task = Task(id=task.id, status=TaskStatus.SUBMITTED, tenant_id=task.tenant_id)
        return final_task

    async def get_task(self, agent_url: str, task_id: str) -> Task:
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        req = types.GetTaskRequest(id=task_id)
        sdk_task = await sdk.get_task(req)
        return _sdk_task_to_local(sdk_task)

    async def cancel_task(self, agent_url: str, task_id: str) -> Task:
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        req = types.CancelTaskRequest(id=task_id)
        sdk_task = await sdk.cancel_task(req)
        return _sdk_task_to_local(sdk_task)

    async def subscribe_task(self, agent_url: str, task_id: str) -> AsyncIterator[Task]:
        """Stream task updates via the SDK's ``subscribe`` method."""
        sdk = await self._get_sdk_client(agent_url)
        types = _import_a2a_types()
        req = types.SubscribeToTaskRequest(id=task_id)
        async for event in sdk.subscribe(req):
            kind = event.WhichOneof("payload") if hasattr(event, "WhichOneof") else None
            if kind == "task":
                yield _sdk_task_to_local(event.task)
            elif kind == "status_update":
                d = MessageToDict(event.status_update, preserving_proto_field_name=True)
                state_name = d.get("status", {}).get("state", "TASK_STATE_WORKING")
                status = _STATE_TO_STATUS.get(state_name, TaskStatus.WORKING)
                yield Task(id=task_id, status=status)

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
