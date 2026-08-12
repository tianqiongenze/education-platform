"""A2A protocol unit tests."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest

from ai_a2a import (
    AgentCard,
    AgentRegistry,
    A2AClient,
    Artifact,
    DataPart,
    FilePart,
    Message,
    Skill,
    Task,
    TaskManager,
    TaskStatus,
    TextPart,
)
from ai_a2a.server import A2AServer, create_a2a_router
from ai_a2a.notification import NotificationManager


# --- AgentCard ---


def test_agent_card_roundtrip():
    card = AgentCard(
        name="grading-agent",
        description="Grades student submissions",
        version="1.2.0",
        url="https://sp04.example.com",
        capabilities=["tasks/send", "tasks/get"],
        skills=[Skill(id="grade", name="Grade", description="auto-grade", tags=["edu"])],
        authentication={"type": "bearer"},
        tenant_id="t1",
    )
    d = card.to_dict()
    assert d["name"] == "grading-agent"
    assert d["skills"][0]["tags"] == ["edu"]
    assert d["authentication"]["type"] == "bearer"

    rebuilt = AgentCard.from_dict(d)
    assert rebuilt.name == card.name
    assert rebuilt.skills[0].id == "grade"
    assert rebuilt.tenant_id == "t1"
    assert json.loads(card.to_json())["version"] == "1.2.0"


# --- Message / Part ---


def test_message_text_part():
    m = Message.text("user", "hello")
    d = m.to_dict()
    assert d["role"] == "user"
    assert d["parts"][0]["kind"] == "text"
    assert d["parts"][0]["text"] == "hello"
    rebuilt = Message.from_dict(d)
    assert isinstance(rebuilt.parts[0], TextPart)
    assert rebuilt.parts[0].text == "hello"


def test_part_polymorphism():
    parts = [TextPart(text="a"), FilePart(uri="s3://x", mime_type="image/png"), DataPart(json_data={"k": 1})]
    msg = Message(role="agent", parts=parts)
    d = msg.to_dict()
    kinds = [p["kind"] for p in d["parts"]]
    assert kinds == ["text", "file", "data"]
    rebuilt = Message.from_dict(d)
    assert isinstance(rebuilt.parts[0], TextPart)
    assert isinstance(rebuilt.parts[1], FilePart)
    assert isinstance(rebuilt.parts[2], DataPart)
    assert rebuilt.parts[2].json_data == {"k": 1}


def test_file_part_bytes_roundtrip():
    fp = FilePart.from_bytes(b"hello", mime_type="text/plain")
    assert fp.to_bytes() == b"hello"
    d = fp.to_dict()
    assert d["kind"] == "file"
    assert FilePart.from_dict(d).to_bytes() == b"hello"


def test_artifact_roundtrip():
    art = Artifact(id="a1", name="result", parts=[TextPart(text="ok")], metadata={"score": 0.9})
    d = art.to_dict()
    rebuilt = Artifact.from_dict(d)
    assert rebuilt.id == "a1"
    assert rebuilt.metadata["score"] == 0.9
    assert isinstance(rebuilt.parts[0], TextPart)


# --- Task lifecycle ---


@pytest.mark.asyncio
async def test_task_manager_local_create_get():
    tm = TaskManager()
    task = Task(message=Message.text("user", "grade this"), tenant_id="t1")
    await tm.create(task)
    fetched = await tm.get(task.id)
    assert fetched is not None
    assert fetched.status == TaskStatus.SUBMITTED
    assert fetched.tenant_id == "t1"


@pytest.mark.asyncio
async def test_task_lifecycle_transitions():
    tm = TaskManager()
    task = Task(message=Message.text("user", "go"), tenant_id="t1")
    await tm.create(task)
    assert TaskManager.can_transition(TaskStatus.SUBMITTED, TaskStatus.WORKING)
    await tm.update(task.id, TaskStatus.WORKING)
    await tm.update(task.id, TaskStatus.COMPLETED, artifacts=[Artifact(id="a1", parts=[TextPart(text="done")])])
    final = await tm.get(task.id)
    assert final is not None
    assert final.status == TaskStatus.COMPLETED
    assert final.artifacts[0].parts[0].text == "done"


@pytest.mark.asyncio
async def test_task_illegal_transition():
    tm = TaskManager()
    task = Task(message=Message.text("user", "go"))
    await tm.create(task)
    # COMPLETED -> WORKING is illegal
    await tm.update(task.id, TaskStatus.WORKING)
    await tm.update(task.id, TaskStatus.COMPLETED)
    with pytest.raises(ValueError):
        await tm.update(task.id, TaskStatus.WORKING)


@pytest.mark.asyncio
async def test_task_cancel():
    tm = TaskManager()
    task = Task(message=Message.text("user", "go"))
    await tm.create(task)
    canceled = await tm.cancel(task.id)
    assert canceled.status == TaskStatus.CANCELED


# --- Registry ---


@pytest.mark.asyncio
async def test_registry_register_list_unregister():
    reg = AgentRegistry()
    card = AgentCard(name="quiz-agent", description="x", version="1.0", url="https://q", tenant_id="t1")
    await reg.register(card)
    got = await reg.get_agent("quiz-agent")
    assert got is not None
    assert got.url == "https://q"
    listed = await reg.list_agents(tenant_id="t1")
    assert any(c.name == "quiz-agent" for c in listed)
    assert await reg.unregister("quiz-agent", tenant_id="t1")
    assert await reg.get_agent("quiz-agent") is None


# --- A2AClient (SDK-backed, mocked) -----------------------------------------
#
# The client delegates to ``a2a.client.Client``. We inject a fake SDK client
# via the ``_sdk_clients`` cache so no real HTTP/agent-card resolution occurs.


class _FakeStreamResponse:
    def __init__(self, task_proto) -> None:
        self.task = task_proto

    def WhichOneof(self, name: str) -> str:  # noqa: D414
        return "task"


class _FakeSdkClient:
    """Stand-in for ``a2a.client.Client`` used by A2AClient."""

    def __init__(self) -> None:
        self.send_calls: list[Any] = []
        self.get_calls: list[str] = []
        self.cancel_calls: list[str] = []

    async def send_message(self, request):
        from a2a.types import Task as A2ATask, TaskState, TaskStatus as A2AStatus

        self.send_calls.append(request)
        sdk_task = A2ATask(
            id="echoed",
            status=A2AStatus(state=TaskState.TASK_STATE_COMPLETED),
        )
        yield _FakeStreamResponse(sdk_task)

    async def get_task(self, request):
        from a2a.types import Task as A2ATask, TaskState, TaskStatus as A2AStatus

        self.get_calls.append(request.id)
        return A2ATask(
            id=request.id,
            status=A2AStatus(state=TaskState.TASK_STATE_COMPLETED),
        )

    async def cancel_task(self, request):
        from a2a.types import Task as A2ATask, TaskState, TaskStatus as A2AStatus

        self.cancel_calls.append(request.id)
        return A2ATask(
            id=request.id,
            status=A2AStatus(state=TaskState.TASK_STATE_CANCELED),
        )

    async def subscribe(self, request):
        from a2a.types import Task as A2ATask, TaskState, TaskStatus as A2AStatus

        yield _FakeStreamResponse(
            A2ATask(id=request.id, status=A2AStatus(state=TaskState.TASK_STATE_WORKING))
        )
        yield _FakeStreamResponse(
            A2ATask(id=request.id, status=A2AStatus(state=TaskState.TASK_STATE_COMPLETED))
        )

    async def close(self) -> None:
        return None


@pytest.mark.asyncio
async def test_a2a_client_send_task():
    client = A2AClient()
    fake = _FakeSdkClient()
    client._sdk_clients["https://agent"] = fake
    task = Task(message=Message.text("user", "hi"), tenant_id="t1")
    result = await client.send_task("https://agent", task)
    assert result.status == TaskStatus.COMPLETED
    assert fake.send_calls  # send_message was invoked
    await client.aclose()


@pytest.mark.asyncio
async def test_a2a_client_get_cancel():
    client = A2AClient()
    fake = _FakeSdkClient()
    client._sdk_clients["https://agent"] = fake
    got = await client.get_task("https://agent", "task-123")
    assert got.status == TaskStatus.COMPLETED
    assert fake.get_calls == ["task-123"]
    canceled = await client.cancel_task("https://agent", "task-123")
    assert canceled.status == TaskStatus.CANCELED
    assert fake.cancel_calls == ["task-123"]
    await client.aclose()


@pytest.mark.asyncio
async def test_a2a_client_subscribe():
    client = A2AClient()
    fake = _FakeSdkClient()
    client._sdk_clients["https://agent"] = fake
    events = []
    async for t in client.subscribe_task("https://agent", "task-9"):
        events.append(t.status)
    assert TaskStatus.WORKING in events
    assert TaskStatus.COMPLETED in events
    await client.aclose()


# --- Server dispatch (no handler = echo) ---


@pytest.mark.asyncio
async def test_server_dispatch_no_handler():
    card = AgentCard(name="svc", description="d", version="1", url="https://svc")
    tm = TaskManager()
    server = A2AServer(card, tm)
    task = Task(message=Message.text("user", "ping"))
    result = await server.dispatch(task)
    # dispatch is async when no handler: should already be COMPLETED
    final = await tm.get(result.id)
    assert final is not None
    assert final.status == TaskStatus.COMPLETED
    assert final.artifacts[0].parts[0].text == "no handler configured"


@pytest.mark.asyncio
async def test_server_router_agent_json_endpoint():
    from litestar import Litestar
    from litestar.testing import TestClient

    card = AgentCard(name="svc", description="d", version="1", url="https://svc", skills=[Skill("s", "S", "desc")])
    server = A2AServer(card, TaskManager())
    router = create_a2a_router(server)
    app = Litestar(route_handlers=[router], openapi_config=None)
    with TestClient(app) as client:
        resp = client.get("/.well-known/agent.json")
        assert resp.status_code == 200
        data = resp.json()
        assert data["name"] == "svc"
        assert data["skills"][0]["id"] == "s"


@pytest.mark.asyncio
async def test_notification_manager_calls_webhook():
    class _MockA2AClient:
        def __init__(self) -> None:
            self.calls: list[tuple[str, Task]] = []

        async def send_notification(self, webhook_url: str, task: Task) -> None:
            self.calls.append((webhook_url, task))

    mock_client = _MockA2AClient()
    nm = NotificationManager(event_bus=None, a2a_client=mock_client)  # type: ignore[arg-type]
    nm.register_webhook("task-1", "https://hook")
    task = Task(id="task-1", status=TaskStatus.COMPLETED)
    await nm.notify(task)
    assert len(mock_client.calls) == 1
    assert mock_client.calls[0][0] == "https://hook"
