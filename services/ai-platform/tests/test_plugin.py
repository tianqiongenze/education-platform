"""Tests for the AI Plugin system (ai_plugin)."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from ai_mcp.client import MCPClient
from ai_mcp.schema import ToolSchema
from ai_plugin.discovery import PluginToolDiscovery
from ai_plugin.loader import PluginLoader
from ai_plugin.manifest import PluginManifest, PluginPermission, PluginSkill
from ai_plugin.registry import PluginRegistry
from ai_plugin.sandbox import PluginSandbox


# ---------------------------------------------------------------- manifest


def _make_manifest(
    plugin_id: str = "p1",
    tenant_whitelist: list[str] | None = None,
    permissions: list[PluginPermission] | None = None,
) -> PluginManifest:
    return PluginManifest(
        plugin_id=plugin_id,
        name="Test Plugin",
        mcp_server_url="http://test/mcp",
        transport="http",
        skills=[PluginSkill(name="search", description="search things")],
        required_permissions=permissions or [PluginPermission.KNOWLEDGE_READ],
        tenant_whitelist=tenant_whitelist or ["t1"],
        version="1.0.0",
        description="A test plugin.",
    )


def test_manifest_roundtrip():
    m = _make_manifest()
    d = m.to_dict()
    again = PluginManifest.from_dict(d)
    assert again.plugin_id == "p1"
    assert again.required_permissions == [PluginPermission.KNOWLEDGE_READ]
    assert again.skills[0].name == "search"
    assert again.tenant_whitelist == ["t1"]


def test_manifest_tenant_whitelist():
    m = _make_manifest(tenant_whitelist=["t1", "t2"])
    assert m.is_allowed_for_tenant("t1") is True
    assert m.is_allowed_for_tenant("t3") is False

    wildcard = _make_manifest(tenant_whitelist=["*"])
    assert wildcard.is_allowed_for_tenant("any") is True


def test_manifest_permission_enum():
    m = _make_manifest(permissions=[PluginPermission.QUIZ_GENERATE, PluginPermission.NETWORK_EGRESS])
    d = m.to_dict()
    assert "quiz:generate" in d["required_permissions"]
    assert "network:egress" in d["required_permissions"]


# ---------------------------------------------------------------- registry


class FakeRedis:
    def __init__(self) -> None:
        self.store: dict[str, str] = {}

    async def get(self, key: str) -> str | None:
        return self.store.get(key)

    async def set(self, key: str, value: str) -> None:
        self.store[key] = value

    async def delete(self, key: str) -> None:
        self.store.pop(key, None)


@pytest.mark.asyncio
async def test_registry_register_and_list():
    reg = PluginRegistry(redis=FakeRedis())
    m = _make_manifest()
    await reg.register(m)
    plugins = await reg.list_plugins("t1")
    assert len(plugins) == 1
    assert plugins[0].plugin_id == "p1"


@pytest.mark.asyncio
async def test_registry_tenant_isolation():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest(plugin_id="p1", tenant_whitelist=["t1"]))
    # t2 should not see p1.
    plugins_t2 = await reg.list_plugins("t2")
    assert all(p.plugin_id != "p1" for p in plugins_t2)


@pytest.mark.asyncio
async def test_registry_unregister():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest())
    assert len(await reg.list_plugins("t1")) == 1
    await reg.unregister("p1", "t1")
    assert len(await reg.list_plugins("t1")) == 0


@pytest.mark.asyncio
async def test_registry_get_plugin():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest())
    got = await reg.get_plugin("p1", "t1")
    assert got is not None and got.name == "Test Plugin"
    miss = await reg.get_plugin("nope", "t1")
    assert miss is None


# ----------------------------------------------------------------- sandbox


@pytest.mark.asyncio
async def test_sandbox_permission_granted():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest(permissions=[PluginPermission.KNOWLEDGE_READ]))
    sandbox = PluginSandbox(registry=reg)
    ok = await sandbox.check_permission("p1", "t1", PluginPermission.KNOWLEDGE_READ)
    assert ok is True


@pytest.mark.asyncio
async def test_sandbox_permission_denied_wrong_perm():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest(permissions=[PluginPermission.KNOWLEDGE_READ]))
    sandbox = PluginSandbox(registry=reg)
    ok = await sandbox.check_permission("p1", "t1", PluginPermission.KNOWLEDGE_WRITE)
    assert ok is False


@pytest.mark.asyncio
async def test_sandbox_permission_denied_wrong_tenant():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest(tenant_whitelist=["t1"]))
    sandbox = PluginSandbox(registry=reg)
    ok = await sandbox.check_permission("p1", "t2", PluginPermission.KNOWLEDGE_READ)
    assert ok is False


@pytest.mark.asyncio
async def test_sandbox_timeout():
    reg = PluginRegistry(redis=FakeRedis())
    sandbox = PluginSandbox(registry=reg, tool_timeout=0.05)

    async def slow() -> str:
        await asyncio.sleep(1.0)
        return "done"

    with pytest.raises((TimeoutError, asyncio.TimeoutError)):
        await sandbox.enforce_timeout(slow())


@pytest.mark.asyncio
async def test_sandbox_guard_blocks_without_permission():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest(permissions=[PluginPermission.KNOWLEDGE_READ]))
    sandbox = PluginSandbox(registry=reg)

    async def noop() -> str:
        return "ok"

    with pytest.raises(PermissionError):
        await sandbox.guard("p1", "t1", PluginPermission.KNOWLEDGE_WRITE, noop())


# ------------------------------------------------------------------ loader


class StubClient(MCPClient):
    """MCPClient subclass that returns canned tool schemas without network."""

    def __init__(self, tools: list[ToolSchema]) -> None:
        super().__init__()
        self._stub_tools = tools

    async def list_tools(self, server_url: str, *, use_cache: bool = True) -> list[ToolSchema]:  # type: ignore[override]
        return self._stub_tools

    async def call_tool(self, server_url: str, tool_name: str, arguments: dict[str, Any], *, timeout: float = 30.0) -> Any:  # type: ignore[override]
        return {"called": tool_name, "args": arguments}


@pytest.mark.asyncio
async def test_loader_load_plugin_fallback():
    tools = [
        ToolSchema(name="search_kb", description="Search KB.", input_schema={"type": "object", "properties": {}}),
        ToolSchema(name="grade", description="Grade.", input_schema={"type": "object", "properties": {}}),
    ]
    client = StubClient(tools=tools)
    loader = PluginLoader(registry=PluginRegistry(redis=FakeRedis()), client=client)
    manifest = _make_manifest()
    loaded = await loader.load_plugin(manifest)
    assert len(loaded) == 2


@pytest.mark.asyncio
async def test_loader_load_all_for_tenant():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest(plugin_id="p1"))
    await reg.register(
        _make_manifest(plugin_id="p2", tenant_whitelist=["t1"])
    )
    tools = [ToolSchema(name="t", description="d", input_schema={"type": "object", "properties": {}})]
    client = StubClient(tools=tools)
    loader = PluginLoader(registry=reg, client=client)
    result = await loader.load_all_for_tenant("t1")
    assert set(result.keys()) == {"p1", "p2"}
    assert len(result["p1"]) == 1


# --------------------------------------------------------------- discovery


@pytest.mark.asyncio
async def test_discovery_keyword_ranking():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest())
    tools = [
        ToolSchema(name="search_kb", description="Search the knowledge base for documents.", input_schema={"type": "object", "properties": {}}),
        ToolSchema(name="grade_submission", description="Grade a student submission.", input_schema={"type": "object", "properties": {}}),
        ToolSchema(name="generate_quiz", description="Generate a quiz for a knowledge point.", input_schema={"type": "object", "properties": {}}),
    ]
    client = StubClient(tools=tools)
    discovery = PluginToolDiscovery(registry=reg, client=client)

    # Query about searching should rank search_kb first.
    results = await discovery.discover_tools("search knowledge", "t1", top_k=3)
    assert results[0].name == "search_kb"

    # Query about quizzes should rank generate_quiz first.
    results = await discovery.discover_tools("generate quiz", "t1", top_k=3)
    assert results[0].name == "generate_quiz"


@pytest.mark.asyncio
async def test_discovery_top_k_limit():
    reg = PluginRegistry(redis=FakeRedis())
    await reg.register(_make_manifest())
    tools = [
        ToolSchema(name=f"tool_{i}", description=f"tool number {i}", input_schema={"type": "object", "properties": {}})
        for i in range(5)
    ]
    client = StubClient(tools=tools)
    discovery = PluginToolDiscovery(registry=reg, client=client)
    results = await discovery.discover_tools("tool", "t1", top_k=2)
    assert len(results) == 2


@pytest.mark.asyncio
async def test_discovery_no_tools():
    reg = PluginRegistry(redis=FakeRedis())
    # No plugins registered for tenant.
    discovery = PluginToolDiscovery(registry=reg, client=StubClient(tools=[]))
    results = await discovery.discover_tools("anything", "t1")
    assert results == []
