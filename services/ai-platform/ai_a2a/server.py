"""A2A server - protocol handling delegated to the official ``a2a-sdk``.

The hand-written JSON-RPC framing and SSE plumbing are gone. Protocol
semantics are delegated to ``a2a.server.request_handlers.DefaultRequestHandler``
backed by an ``AgentExecutor`` and ``InMemoryTaskStore``. The SDK ships
Starlette route creators; ``create_a2a_router`` mounts them onto Litestar
via ``@asgi`` so callers keep importing a Litestar ``Router``.
"""

from __future__ import annotations

import asyncio
from typing import Any

import structlog
from litestar import Router, get
from litestar.handlers.asgi_handlers import asgi

from .agent_card import AgentCard
from .message import Artifact, Message, TextPart
from .notification import NotificationManager
from .task import Task, TaskManager, TaskStatus

logger = structlog.get_logger(__name__)


# --- SDK lazy imports -------------------------------------------------------


def _import_a2a_sdk_server() -> Any:
    from a2a.server.request_handlers import DefaultRequestHandler
    from a2a.server.tasks import InMemoryTaskStore
    from a2a.server.agent_execution import AgentExecutor, RequestContext
    from a2a.server.events.event_queue import EventQueue
    from a2a.server.tasks import TaskUpdater
    from a2a.server.routes.agent_card_routes import (
        create_agent_card_routes,
        agent_card_to_dict,
    )
    from a2a.server.routes.jsonrpc_routes import create_jsonrpc_routes
    from a2a.server.routes.rest_routes import create_rest_routes

    return {
        "DefaultRequestHandler": DefaultRequestHandler,
        "InMemoryTaskStore": InMemoryTaskStore,
        "AgentExecutor": AgentExecutor,
        "RequestContext": RequestContext,
        "EventQueue": EventQueue,
        "TaskUpdater": TaskUpdater,
        "create_agent_card_routes": create_agent_card_routes,
        "agent_card_to_dict": agent_card_to_dict,
        "create_jsonrpc_routes": create_jsonrpc_routes,
        "create_rest_routes": create_rest_routes,
    }


def _import_a2a_types() -> Any:
    from a2a import types

    return types


# --- local AgentCard -> SDK AgentCard ---------------------------------------


def _local_card_to_sdk(card: AgentCard) -> Any:
    """Convert the local ``AgentCard`` dataclass to the SDK protobuf type."""
    types = _import_a2a_types()
    from google.protobuf.json_format import ParseDict

    skills_pb = []
    for s in card.skills:
        skills_pb.append(
            {
                "id": s.id,
                "name": s.name,
                "description": s.description,
                "tags": list(s.tags),
            }
        )
    card_dict: dict[str, Any] = {
        "name": card.name,
        "description": card.description,
        "version": card.version,
        "skills": skills_pb,
    }
    return ParseDict(card_dict, types.AgentCard())


# --- AgentExecutor adapter --------------------------------------------------


class _HandlerExecutorAdapter:
    """Adapter wrapping a ``callable(task) -> Task`` handler as an AgentExecutor.

    The SDK's ``AgentExecutor`` is an ABC with ``execute(context, event_queue)``
    and ``cancel(context, event_queue)``. We adapt the platform's simpler
    ``agent_handler(task) -> Task`` callback into that contract.
    """

    def __init__(self, server: "A2AServer") -> None:
        self._server = server

    async def execute(self, context: Any, event_queue: Any) -> None:
        sdk = _import_a2a_sdk_server()
        types = _import_a2a_types()
        TaskUpdater = sdk["TaskUpdater"]

        task_id = context.task_id
        context_id = context.context_id
        updater = TaskUpdater(event_queue, task_id, context_id)
        await updater.start_work()

        local_task = Task(id=task_id, status=TaskStatus.WORKING)
        local_task.thread_id = context_id

        try:
            if self._server.agent_handler is None:
                updater.add_artifact([types.Part(text="no handler configured")])
                await updater.complete()
                await self._server._notify_local(local_task, TaskStatus.COMPLETED)
                return

            result = await self._server.agent_handler(local_task)
            if result.status == TaskStatus.INPUT_REQUIRED:
                await updater.requires_input()
            else:
                for art in result.artifacts:
                    parts_pb = []
                    for p in art.parts:
                        if hasattr(p, "text"):
                            parts_pb.append(types.Part(text=p.text))
                    if parts_pb:
                        updater.add_artifact(parts_pb)
                await updater.complete()
            await self._server._notify_local(result, result.status)
        except Exception as exc:  # noqa: BLE001
            logger.error("a2a.handler_failed", task_id=task_id, error=str(exc))
            await updater.failed()

    async def cancel(self, context: Any, event_queue: Any) -> None:  # noqa: D102
        sdk = _import_a2a_sdk_server()
        TaskUpdater = sdk["TaskUpdater"]
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        await updater.cancel()


# --- A2AServer --------------------------------------------------------------


class A2AServer:
    """Holds A2A server-side state: card, task manager, notifications.

    Protocol handling is delegated to a lazily-built
    ``a2a.server.DefaultRequestHandler``. The legacy ``dispatch``/``subscribe_sse``
    surface is retained for callers that exercise the server in-process
    (tests, direct invocation).
    """

    def __init__(
        self,
        card: AgentCard,
        task_manager: TaskManager,
        notification_manager: NotificationManager | None = None,
        *,
        agent_handler: Any | None = None,
    ) -> None:
        self.card = card
        self.tasks = task_manager
        self.notifications = notification_manager
        self.agent_handler = agent_handler
        self._subscribers: dict[str, list[asyncio.Queue[Task]]] = {}
        self._request_handler: Any | None = None

    # --- in-process dispatch (kept for tests / direct callers) -------------

    def subscribe_sse(self, task_id: str) -> asyncio.Queue[Task]:
        q: asyncio.Queue[Task] = asyncio.Queue()
        self._subscribers.setdefault(task_id, []).append(q)
        return q

    def unsubscribe_sse(self, task_id: str, q: asyncio.Queue[Task]) -> None:
        subs = self._subscribers.get(task_id, [])
        if q in subs:
            subs.remove(q)
        if not subs:
            self._subscribers.pop(task_id, None)

    async def dispatch(self, task: Task) -> Task:
        """Create task and dispatch to agent handler (in-process)."""
        await self.tasks.create(task)
        if self.agent_handler is None:
            await self.tasks.update(task.id, TaskStatus.WORKING)
            await self._notify_local(task, TaskStatus.WORKING)
            artifact = Artifact(
                id=task.id, name="echo", parts=[TextPart(text="no handler configured")]
            )
            updated = await self.tasks.update(
                task.id, TaskStatus.COMPLETED, artifacts=[artifact]
            )
            await self._notify_local(updated, TaskStatus.COMPLETED)
            return updated
        asyncio.create_task(self._run_handler(task))
        return task

    async def _run_handler(self, task: Task) -> None:
        try:
            await self.tasks.update(task.id, TaskStatus.WORKING)
            await self._notify_local(task, TaskStatus.WORKING)
            result = await self.agent_handler(task)  # type: ignore[misc]
            if result.status == TaskStatus.INPUT_REQUIRED:
                await self.tasks.update(
                    task.id, TaskStatus.INPUT_REQUIRED, message=result.message
                )
            else:
                await self.tasks.update(
                    task.id,
                    TaskStatus.COMPLETED,
                    artifacts=result.artifacts,
                    message=result.message,
                )
            updated = await self.tasks.get(task.id)
            if updated is not None:
                await self._notify_local(updated, updated.status)
        except Exception as exc:  # noqa: BLE001
            logger.error("a2a.handler_failed", task_id=task.id, error=str(exc))
            await self.tasks.update(task.id, TaskStatus.FAILED, error=str(exc))
            failed = await self.tasks.get(task.id)
            if failed is not None:
                await self._notify_local(failed, TaskStatus.FAILED)

    async def _notify_local(self, task: Task, status: TaskStatus) -> None:
        """Push to SSE subscribers and fire webhooks/Hermes."""
        for q in self._subscribers.get(task.id, []):
            try:
                q.put_nowait(task)
            except asyncio.QueueFull:  # pragma: no cover
                logger.warning("a2a.sse_queue_full", task_id=task.id)
        if self.notifications is not None:
            await self.notifications.notify(task)

    async def _notify_and_publish(self, task: Task) -> None:
        """Backwards-compat alias for the old private name."""
        await self._notify_local(task, task.status)

    # --- SDK request handler (HTTP path) -----------------------------------

    def build_request_handler(self) -> Any:
        """Build (and cache) an a2a-sdk ``DefaultRequestHandler``."""
        if self._request_handler is not None:
            return self._request_handler
        sdk = _import_a2a_sdk_server()
        sdk_card = _local_card_to_sdk(self.card)
        store = sdk["InMemoryTaskStore"]()
        executor = _HandlerExecutorAdapter(self)
        handler = sdk["DefaultRequestHandler"](
            agent_executor=executor,
            task_store=store,
            agent_card=sdk_card,
        )
        self._request_handler = handler
        return handler


# --- Litestar router --------------------------------------------------------


def create_a2a_router(server: A2AServer) -> Router:
    """Build a Litestar router exposing A2A endpoints.

    The JSON-RPC and REST endpoints are produced by a2a-sdk's route creators
    (Starlette ``Route`` objects) and mounted into Litestar via ``@asgi``.
    The agent-card endpoint is served natively so callers keep the same
    ``/.well-known/agent.json`` path.
    """

    @get("/.well-known/agent.json")
    async def agent_card() -> dict[str, Any]:
        return server.card.to_dict()

    @asgi("/v1/a2a/jsonrpc", is_mount=True, copy_scope=False)
    async def jsonrpc_asgi(scope: dict[str, Any], receive: Any, send: Any) -> None:
        sdk = _import_a2a_sdk_server()
        handler = server.build_request_handler()
        routes = sdk["create_jsonrpc_routes"](handler, rpc_url="/v1/a2a/jsonrpc")
        from starlette.applications import Starlette

        await Starlette(routes=routes)(scope, receive, send)

    @asgi("/v1/a2a/tasks", is_mount=True, copy_scope=False)
    async def rest_asgi(scope: dict[str, Any], receive: Any, send: Any) -> None:
        sdk = _import_a2a_sdk_server()
        handler = server.build_request_handler()
        routes = sdk["create_rest_routes"](handler)
        from starlette.applications import Starlette

        await Starlette(routes=routes)(scope, receive, send)

    @asgi("/v1/a2a/tasks/{task_id:str}/subscribe", is_mount=True, copy_scope=False)
    async def subscribe_asgi(scope: dict[str, Any], receive: Any, send: Any) -> None:
        sdk = _import_a2a_sdk_server()
        handler = server.build_request_handler()
        routes = sdk["create_rest_routes"](handler)
        from starlette.applications import Starlette

        await Starlette(routes=routes)(scope, receive, send)

    return Router(
        path="/",
        route_handlers=[agent_card, jsonrpc_asgi, rest_asgi, subscribe_asgi],
    )
