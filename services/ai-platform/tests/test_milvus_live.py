"""Live integration test: Milvus 3.0 / pymilvus for ep-memory L3 (D-052).

Validates the *real* Milvus path in
``ep_memory.l3_organizational.OrganizationalMemory`` against a running
Milvus instance (K8s deployment ``milvus`` in namespace
``ekb-m2-aws-us-east-1``), port-forwarded to ``localhost:19530``.

Iron Law 0 compliance: this test NEVER silently passes. If Milvus is
unreachable or pymilvus is not installed, the test is *skipped* with a
detailed reason (no silent in-memory fallback masquerading as success).

Preconditions (run before invoking with EP_LIVE=1):

    # 1. Ensure the Milvus deployment has replicas >= 1 (already 1/1 in
    #    ekb-m2-aws-us-east-1 as of 2026-08-06).
    kubectl get deploy -n ekb-m2-aws-us-east-1 milvus
    # 2. Port-forward Milvus gRPC to localhost.
    kubectl port-forward -n ekb-m2-aws-us-east-1 svc/milvus 19530:19530 &
    # 3. Install the client (matches the server image milvusdb/milvus:v2.5.0).
    pip install pymilvus==2.5.0
    # 4. Run with the live gate.
    EP_LIVE=1 python -m pytest tests/test_milvus_live.py -v -s

The existing ``test_live_milvus.py`` exercises ``ai_rag.MilvusVectorStore``;
this file exercises the ep-memory L3 ``OrganizationalMemory`` path (D-052),
including the full add → search → promote → delete lifecycle.
"""

from __future__ import annotations

import os
import uuid

import pytest

pytestmark = pytest.mark.live

# Iron Law 0: skip (not silently pass) when the live gate is off or the
# client/server is unavailable. Each skip carries a concrete reason.
_LIVE_ENABLED = os.getenv("EP_LIVE", "0") == "1"
_SKIP_NO_LIVE = (
    "EP_LIVE=1 not set. To run live Milvus tests:\n"
    "  1. kubectl port-forward -n ekb-m2-aws-us-east-1 svc/milvus 19530:19530\n"
    "  2. pip install pymilvus==2.5.0\n"
    "  3. EP_LIVE=1 python -m pytest tests/test_milvus_live.py -v -s"
)


def _require_pymilvus():
    """Import pymilvus or skip. Returns the MilvusClient class."""
    try:
        from pymilvus import MilvusClient  # type: ignore

        return MilvusClient
    except ImportError as exc:  # pragma: no cover - environment-dependent
        pytest.skip(
            f"pymilvus not installed: {exc}. Install with: pip install pymilvus==2.50"
        )


def _milvus_reachable(host: str, port: int, timeout: float = 5.0) -> bool:
    """TCP probe the Milvus gRPC port. Returns True if a connection opens."""
    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


@pytest.fixture(scope="module")
def live_client():
    """Yield a connected MilvusClient against the live instance, or skip."""
    if not _LIVE_ENABLED:
        pytest.skip(_SKIP_NO_LIVE)

    MilvusClient = _require_pymilvus()

    host = os.getenv("MILVUS_HOST", "localhost")
    port = int(os.getenv("MILVUS_PORT", "19530"))
    if not _milvus_reachable(host, port):
        pytest.skip(
            f"Milvus unreachable at {host}:{port}. Run: "
            f"kubectl port-forward -n ekb-m2-aws-us-east-1 svc/milvus {port}:19530"
        )

    client = MilvusClient(uri=f"http://{host}:{port}", timeout=15)
    # Touch the server to confirm the gRPC channel is actually ready.
    try:
        client.list_collections()
    except Exception as exc:  # pragma: no cover - environment-dependent
        pytest.skip(f"Milvus gRPC channel not ready at {host}:{port}: {exc}")

    yield client

    # Cleanup any collections this test module created (prefix mem_org_live_).
    try:
        for name in client.list_collections():
            if name.startswith("mem_org_live_"):
                client.drop_collection(name)
    except Exception:  # pragma: no cover - best-effort cleanup
        pass


def test_milvus_connection(live_client) -> None:
    """A live MilvusClient can list collections (empty or not)."""
    cols = live_client.list_collections()
    assert isinstance(cols, list)


def test_milvus_collection_create_insert_search_delete(live_client) -> None:
    """End-to-end: create collection, insert, vector search, then delete.

    Exercises the raw MilvusClient API the same way
    ``OrganizationalMemory._ensure_collection`` does, so a failure here
    points at the infrastructure rather than the ep-memory wrapper.
    """
    from pymilvus import CollectionSchema, DataType, FieldSchema  # type: ignore

    collection = f"mem_org_live_raw_{uuid.uuid4().hex[:8]}"
    dim = 16

    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=128),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535),
        FieldSchema(name="tenant_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(
            name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim
        ),
    ]
    schema = CollectionSchema(fields=fields, enable_dynamic_field=True)
    live_client.create_collection(collection, schema=schema)
    assert live_client.has_collection(collection)

    rows = [
        {
            "id": f"live-{i}",
            "content": f"document number {i}",
            "tenant_id": "t-live",
            "embedding": [float(i + 1) / 10.0] * dim,
        }
        for i in range(3)
    ]
    live_client.insert(collection, data=rows)

    # Milvus needs an index before search on a vector field.
    index_params = live_client.prepare_index_params()
    index_params.add_index(
        field_name="embedding",
        index_type="FLAT",
        metric_type="COSINE",
    )
    live_client.create_index(collection, index_params=index_params)
    live_client.load_collection(collection)

    # Query with the same vector as live-0 ([0.1]*16) so it is the exact
    # nearest neighbour (cosine == 1.0). Avoid an all-zero query vector,
    # whose cosine similarity is undefined and yields indeterminate ranking.
    results = live_client.search(
        collection_name=collection,
        data=[[0.1] * dim],
        limit=3,
        output_fields=["id", "content", "tenant_id"],
    )
    assert results, "search returned no result sets"
    hits = results[0] if isinstance(results, list) else results
    assert len(hits) >= 1
    # With COSINE similarity, all three documents are collinear with the
    # query (cosine == 1.0), so Milvus may return them in any order. We
    # therefore assert that the inserted documents are all retrievable and
    # that the top hit is one of them, rather than relying on tie-break
    # ordering.
    returned_ids = [h.get("id") for h in hits]
    assert set(returned_ids) == {"live-0", "live-1", "live-2"}

    live_client.drop_collection(collection)
    assert not live_client.has_collection(collection)


# --- ep-memory L3 OrganizationalMemory full lifecycle ----------------------


def _live_config():
    """MemoryConfig pointed at the live Milvus, NOT in dev-only mode."""
    # We deliberately do NOT set EP_DEV_ONLY so the production code path
    # (real Milvus, no in-memory fallback) is exercised.
    from ep_memory.config import MemoryConfig

    return MemoryConfig(
        redis_url=None,
        mem0_enabled=False,
        milvus_host=os.getenv("MILVUS_HOST", "localhost"),
        milvus_port=int(os.getenv("MILVUS_PORT", "19530")),
        l3_collection_prefix="mem_org_live",  # isolate from other tests
    )


@pytest.fixture()
def l3_store():
    if not _LIVE_ENABLED:
        pytest.skip(_SKIP_NO_LIVE)
    _require_pymilvus()
    host = os.getenv("MILVUS_HOST", "localhost")
    port = int(os.getenv("MILVUS_PORT", "19530"))
    if not _milvus_reachable(host, port):
        pytest.skip(f"Milvus unreachable at {host}:{port} (port-forward down?)")

    # EP_DEV_ONLY must be unset so the production path is taken.
    os.environ.pop("EP_DEV_ONLY", None)
    from ep_memory.l3_organizational import OrganizationalMemory
    from ep_memory.models import Memory

    store = OrganizationalMemory(_live_config())
    yield store, Memory
    # Cleanup tenant collections created during the test.
    try:
        from pymilvus import MilvusClient  # type: ignore

        client = MilvusClient(uri=f"http://{host}:{port}", timeout=10)
        for name in client.list_collections():
            if name.startswith("mem_org_live_"):
                client.drop_collection(name)
    except Exception:  # pragma: no cover - best-effort cleanup
        pass


@pytest.mark.asyncio
async def test_l3_add_search(l3_store) -> None:
    """L3 add inserts into Milvus and search retrieves by similarity."""
    store, Memory = l3_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"
    mem = Memory(
        tenant_id=tenant,
        user_id="u-live",
        content="The company remote-work policy allows Fridays.",
    )
    added = await store.add(mem)
    assert added.id == mem.id

    found = await store.search("remote work policy", tenant, top_k=5)
    assert len(found) >= 1
    assert any(m.tenant_id == tenant for m in found)


@pytest.mark.asyncio
async def test_l3_promote_search(l3_store) -> None:
    """Promote an L2-style memory into L3 and verify it is searchable."""
    store, Memory = l3_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"
    src = Memory(
        tenant_id=tenant,
        user_id="u-live",
        content="Quarterly OKR review happens in March.",
    )
    promoted = await store.promote(src)
    assert promoted.metadata.get("promoted_from") == src.id
    assert promoted.user_id == ""  # org memories are tenant-scoped, not user-scoped

    found = await store.search("OKR review", tenant, top_k=5)
    assert len(found) >= 1
    assert any(m.id == promoted.id for m in found)


@pytest.mark.asyncio
async def test_l3_admin_delete(l3_store) -> None:
    """Admin delete removes the memory from Milvus; non-admin raises."""
    store, Memory = l3_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"
    mem = Memory(tenant_id=tenant, content="temporary org fact to delete")
    await store.add(mem)

    # Non-admin must be refused.
    with pytest.raises(PermissionError):
        await store.delete(mem.id, is_admin=False)

    ok = await store.delete(mem.id, is_admin=True)
    assert ok is True


@pytest.mark.asyncio
async def test_l3_list_all(l3_store) -> None:
    """list_all returns tenant-scoped org memories from Milvus."""
    store, Memory = l3_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"
    await store.add(Memory(tenant_id=tenant, content="fact A"))
    await store.add(Memory(tenant_id=tenant, content="fact B"))
    listed = await store.list_all(tenant)
    assert len(listed) >= 2
    assert all(m.tenant_id == tenant for m in listed)
