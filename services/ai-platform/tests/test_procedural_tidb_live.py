"""Live integration test: TiDB recursive CTE for procedural memory (D-053).

D-053 replaced the (terminated) Neo4j procedural-memory graph with TiDB
recursive CTE, keeping the dependency surface to a single already-present
component. This test validates the *real* TiDB path in
``ep_memory.procedural_store.ProceduralMemory`` against the running TiDB
instance (K8s deployment ``tidb-cluster-tidb`` in namespace
``ekb-m2-aws-us-east-1``), port-forwarded to ``localhost:4000``.

Iron Law 0 compliance: this test NEVER silently passes. If TiDB is
unreachable it is *skipped* with a concrete reason; there is no in-memory
fallback when EP_LIVE=1 (the production code path is exercised directly).

Preconditions (run before invoking with EP_LIVE=1):

    # 1. Ensure the TiDB deployment has replicas >= 1 (1/1 as of 2026-08-06).
    kubectl get deploy -n ekb-m2-aws-us-east-1 tidb-cluster-tidb
    # 2. Port-forward the TiDB MySQL protocol port.
    kubectl port-forward -n ekb-m2-aws-us-east-1 svc/tidb-cluster-tidb 4000:4000 &
    # 3. Run with the live gate.
    EP_LIVE=1 python -m pytest tests/test_procedural_tidb_live.py -v -s

Validated live on 2026-08-06 against TiDB v8.5.5: the recursive CTE
returns a 3-level skill path (addition -> multiplication -> algebra)
matching the in-memory BFS behaviour.
"""

from __future__ import annotations

import os
import uuid

import pytest

pytestmark = pytest.mark.live

_LIVE_ENABLED = os.getenv("EP_LIVE", "0") == "1"
_SKIP_NO_LIVE = (
    "EP_LIVE=1 not set. To run live TiDB procedural tests:\n"
    "  1. kubectl port-forward -n ekb-m2-aws-us-east-1 svc/tidb-cluster-tidb 4000:4000\n"
    "  2. EP_LIVE=1 python -m pytest tests/test_procedural_tidb_live.py -v -s"
)

_TIDB_HOST = os.getenv("TIDB_HOST", "localhost")
_TIDB_PORT = int(os.getenv("TIDB_PORT", "4000"))
_TIDB_USER = os.getenv("TIDB_USER", "root")
_TIDB_DB = os.getenv("TIDB_DB", "test")


def _tidb_reachable(host: str, port: int, timeout: float = 5.0) -> bool:
    """TCP probe the TiDB MySQL protocol port."""
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


def _require_pymysql():
    try:
        import pymysql  # type: ignore  # noqa: F401

        return pymysql
    except ImportError as exc:  # pragma: no cover
        pytest.skip(f"pymysql not installed: {exc}")


@pytest.fixture()
def procedural_store():
    """Yield a ProceduralMemory wired to live TiDB (NOT dev-only mode)."""
    if not _LIVE_ENABLED:
        pytest.skip(_SKIP_NO_LIVE)
    _require_pymysql()
    if not _tidb_reachable(_TIDB_HOST, _TIDB_PORT):
        pytest.skip(
            f"TiDB unreachable at {_TIDB_HOST}:{_TIDB_PORT}. Run: "
            f"kubectl port-forward -n ekb-m2-aws-us-east-1 "
            f"svc/tidb-cluster-tidb {_TIDB_PORT}:4000"
        )

    # EP_DEV_ONLY must be unset so the production (real TiDB) path runs.
    os.environ.pop("EP_DEV_ONLY", None)
    # tidb_url uses the pymysql-style connection string expected by the store.
    os.environ["TIDB_URL"] = f"{_TIDB_HOST}:{_TIDB_PORT}"
    # Use a per-run table suffix so concurrent runs do not collide and so
    # cleanup is deterministic.
    suffix = uuid.uuid4().hex[:8]
    os.environ["EP_MEM_PROCEDURAL_TABLE"] = f"mem_procedural_live_{suffix}"

    from ep_memory.config import MemoryConfig
    from ep_memory.procedural_store import ProceduralMemory

    cfg = MemoryConfig.load()
    cfg.tidb_url = os.environ["TIDB_URL"]
    cfg.procedural_table = os.environ["EP_MEM_PROCEDURAL_TABLE"]

    store = ProceduralMemory(cfg)
    yield store

    # Cleanup: drop the per-run tables.
    try:
        import pymysql  # type: ignore

        conn = pymysql.connect(
            host=_TIDB_HOST,
            port=_TIDB_PORT,
            user=_TIDB_USER,
            database=_TIDB_DB,
            autocommit=True,
        )
        cur = conn.cursor()
        cur.execute(f"DROP TABLE IF EXISTS `{cfg.procedural_table}_edges`")
        cur.execute(f"DROP TABLE IF EXISTS `{cfg.procedural_table}`")
        conn.close()
    except Exception:  # pragma: no cover - best-effort cleanup
        pass


@pytest.mark.asyncio
async def test_add_skill_and_edge(procedural_store) -> None:
    """add_skill + add_edge insert rows into TiDB; traverse sees them."""
    store = procedural_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"

    await store.add_skill(tenant, "addition", "basic arithmetic")
    await store.add_skill(tenant, "multiplication", "repeated addition")
    ok = await store.add_edge(tenant, "addition", "multiplication", "prerequisite")
    assert ok is True

    path = await store.traverse(tenant, "addition", max_depth=3)
    names = [p["skill_name"] for p in path]
    assert "addition" in names
    assert "multiplication" in names


@pytest.mark.asyncio
async def test_recursive_cte_multi_hop(procedural_store) -> None:
    """Recursive CTE returns a 3-level path: addition -> multiplication -> algebra.

    This is the core D-053 validation: TiDB's recursive CTE replaces the
    Neo4j graph traversal and correctly returns a bounded multi-hop path
    with depth and path columns.
    """
    store = procedural_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"

    await store.add_skill(tenant, "addition", "add")
    await store.add_skill(tenant, "multiplication", "mul")
    await store.add_skill(tenant, "algebra", "alg")
    await store.add_edge(tenant, "addition", "multiplication")
    await store.add_edge(tenant, "multiplication", "algebra")

    path = await store.traverse(tenant, "addition", max_depth=5)
    # Anchor (depth 0) plus two hops.
    assert len(path) == 3
    assert path[0]["skill_name"] == "addition"
    assert path[0]["depth"] == 0
    assert path[1]["skill_name"] == "multiplication"
    assert path[1]["depth"] == 1
    assert path[2]["skill_name"] == "algebra"
    assert path[2]["depth"] == 2
    # Path string grows with each hop.
    assert "->" in path[1]["path"]
    assert path[2]["path"].count("->") == 2


@pytest.mark.asyncio
async def test_find_path(procedural_store) -> None:
    """find_path returns the ordered skill names from source to target."""
    store = procedural_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"

    await store.add_skill(tenant, "addition")
    await store.add_skill(tenant, "multiplication")
    await store.add_skill(tenant, "algebra")
    await store.add_edge(tenant, "addition", "multiplication")
    await store.add_edge(tenant, "multiplication", "algebra")

    names = await store.find_path(tenant, "addition", "algebra")
    assert names is not None
    assert names == ["addition", "multiplication", "algebra"]


@pytest.mark.asyncio
async def test_traverse_respects_max_depth(procedural_store) -> None:
    """max_depth bounds the recursive CTE; deeper nodes are not returned."""
    store = procedural_store
    tenant = f"t_{uuid.uuid4().hex[:6]}"

    for skill in ("a", "b", "c", "d"):
        await store.add_skill(tenant, skill)
    await store.add_edge(tenant, "a", "b")
    await store.add_edge(tenant, "b", "c")
    await store.add_edge(tenant, "c", "d")

    shallow = await store.traverse(tenant, "a", max_depth=1)
    assert {p["skill_name"] for p in shallow} == {"a", "b"}

    full = await store.traverse(tenant, "a", max_depth=5)
    assert {p["skill_name"] for p in full} == {"a", "b", "c", "d"}
