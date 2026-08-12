"""Hermes event bus unit tests (in-memory fallback mode)."""

from __future__ import annotations

import asyncio

import pytest

from ai_hermes import AgentEvent, EventBus, EventRouter, EventType


@pytest.fixture
def bus() -> EventBus:
    return EventBus(enabled=False)


# --- Event serialization ---


def test_agent_event_roundtrip():
    ev = AgentEvent(
        event_type=EventType.GRADING_COMPLETED.value,
        tenant_id="t1",
        agent_id="grading-agent",
        task_id="task-9",
        payload={"score": 0.88},
    )
    d = ev.to_dict()
    assert d["event_type"] == "GRADING_COMPLETED"
    assert d["payload"]["score"] == 0.88
    rebuilt = AgentEvent.from_dict(d)
    assert rebuilt.event_type == "GRADING_COMPLETED"
    assert rebuilt.task_id == "task-9"
    assert rebuilt.payload == {"score": 0.88}


def test_event_type_enum_values():
    assert EventType.SUBMISSION_CREATED.value == "SUBMISSION_CREATED"
    assert EventType.TASK_STATUS_CHANGED.value == "TASK_STATUS_CHANGED"


# --- EventBus in-memory fallback ---


@pytest.mark.asyncio
async def test_bus_publish_subscribe_inmemory(bus: EventBus):
    received: list[AgentEvent] = []

    async def handler(event: AgentEvent) -> None:
        received.append(event)

    sub_id = await bus.subscribe(EventType.QUIZ_GENERATED.value, handler)
    assert sub_id

    event = AgentEvent(event_type=EventType.QUIZ_GENERATED.value, tenant_id="t1", payload={"quiz_id": "q1"})
    await bus.publish(event)

    # Allow consumer task to drain
    await asyncio.sleep(0.05)
    assert len(received) == 1
    assert received[0].payload["quiz_id"] == "q1"
    await bus.stop()


@pytest.mark.asyncio
async def test_bus_filters_by_event_type(bus: EventBus):
    received: list[AgentEvent] = []

    async def handler(event: AgentEvent) -> None:
        received.append(event)

    await bus.subscribe(EventType.SESSION_ENDED.value, handler)
    # Publish unrelated event
    await bus.publish(AgentEvent(event_type=EventType.QUIZ_GENERATED.value))
    await bus.publish(AgentEvent(event_type=EventType.SESSION_ENDED.value, tenant_id="t1"))
    await asyncio.sleep(0.05)
    assert len(received) == 1
    assert received[0].event_type == "SESSION_ENDED"
    await bus.stop()


@pytest.mark.asyncio
async def test_bus_unsubscribe(bus: EventBus):
    received: list[AgentEvent] = []

    async def handler(event: AgentEvent) -> None:
        received.append(event)

    sub_id = await bus.subscribe(EventType.LEARNPATH_UPDATED.value, handler)
    bus.unsubscribe(sub_id)
    await bus.publish(AgentEvent(event_type=EventType.LEARNPATH_UPDATED.value))
    await asyncio.sleep(0.05)
    assert received == []
    await bus.stop()


def test_bus_fallback_mode_warns(bus: EventBus):
    assert bus.enabled is False


# --- EventRouter ---


@pytest.mark.asyncio
async def test_router_default_rules():
    router = EventRouter()
    event = AgentEvent(event_type=EventType.SUBMISSION_CREATED.value, tenant_id="t1")
    targets = await router.route(event)
    assert "grading-agent" in targets


@pytest.mark.asyncio
async def test_router_no_match_no_llm():
    router = EventRouter()
    event = AgentEvent(event_type="UNKNOWN_EVENT", tenant_id="t1")
    targets = await router.route(event)
    assert targets == []


@pytest.mark.asyncio
async def test_router_set_rule_overrides():
    router = EventRouter()
    router.set_rule("SUBMISSION_CREATED", ["custom-agent"])
    event = AgentEvent(event_type=EventType.SUBMISSION_CREATED.value)
    targets = await router.route(event)
    assert targets == ["custom-agent"]


@pytest.mark.asyncio
async def test_router_grading_completed_multi_target():
    router = EventRouter()
    event = AgentEvent(event_type=EventType.GRADING_COMPLETED.value)
    targets = await router.route(event)
    assert "learnpath-agent" in targets
    assert "notification-agent" in targets
