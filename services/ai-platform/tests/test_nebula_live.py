"""Live integration test: NebulaGraph 3.x (D-053) via nebula-python.

D-053 selected NebulaGraph 3.x as the graph database (Apache 2.0, RAFT
distributed HA) — replacing the terminated Neo4j dependency. This test
validates a real NebulaGraph connection: space creation, tag/edge schema,
vertex/edge insertion, and nGQL graph traversal queries.

Iron Law 0 compliance: this test NEVER silently passes. If NebulaGraph is
unreachable or ``nebula-python`` is not installed, the test is *skipped*
with a detailed reason.

Preconditions (run before invoking with EP_LIVE=1):

    # 1. Deploy NebulaGraph 3.x into the K8s cluster (NOT yet deployed as
    #    of 2026-08-06 — graphd/metad/storaged services absent). Example:
    #    helm repo add nebula https://vesoft-inc.github.io/nebula-helm-chart
    #    helm install nebula nebula/nebula-cluster -n ekb-m2-aws-us-east-1
    # 2. Port-forward graphd (gRPC 9669) to localhost.
    #    kubectl port-forward -n ekb-m2-aws-us-east-1 svc/nebula-graphd 9669:9669 &
    # 3. Install the Python client.
    #    pip install nebula-python==3.8.0
    # 4. Run with the live gate.
    #    EP_LIVE=1 python -m pytest tests/test_nebula_live.py -v -s

Status (2026-08-06): NebulaGraph is NOT deployed in the cluster, so this
suite is expected to skip. The skeleton is in place to activate once the
helm chart is installed.
"""

from __future__ import annotations

import os
import uuid

import pytest

pytestmark = pytest.mark.live

_LIVE_ENABLED = os.getenv("EP_LIVE", "0") == "1"
_SKIP_NO_LIVE = (
    "EP_LIVE=1 not set. To run live NebulaGraph tests:\n"
    "  1. helm install nebula nebula/nebula-cluster -n ekb-m2-aws-us-east-1\n"
    "  2. kubectl port-forward -n ekb-m2-aws-us-east-1 svc/nebula-graphd 9669:9669\n"
    "  3. pip install nebula-python==3.8.0\n"
    "  4. EP_LIVE=1 python -m pytest tests/test_nebula_live.py -v -s"
)

# Default NebulaGraph credentials (override via env if customized).
_NEBULA_HOST = os.getenv("NEBULA_HOST", "localhost")
_NEBULA_PORT = int(os.getenv("NEBULA_PORT", "9669"))
_NEBULA_USER = os.getenv("NEBULA_USER", "root")
_NEBULA_PASSWORD = os.getenv("NEBULA_PASSWORD", "nebula")


def _nebula_reachable(host: str, port: int, timeout: float = 5.0) -> bool:
    """TCP probe the NebulaGraph graphd gRPC port."""
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


def _require_nebula_client():
    """Import the nebula-python ConnectionPool or skip."""
    try:
        from nebula3.gclient.net import ConnectionPool  # type: ignore
        from nebula3.Config import Config as NebulaConfig  # type: ignore

        return ConnectionPool, NebulaConfig
    except ImportError as exc:  # pragma: no cover - environment-dependent
        pytest.skip(
            f"nebula-python not installed: {exc}. "
            "Install with: pip install nebula-python==3.8.0"
        )


@pytest.fixture(scope="module")
def nebula_session():
    """Yield an authenticated NebulaGraph session, or skip with a reason."""
    if not _LIVE_ENABLED:
        pytest.skip(_SKIP_NO_LIVE)

    ConnectionPool, NebulaConfig = _require_nebula_client()

    if not _nebula_reachable(_NEBULA_HOST, _NEBULA_PORT):
        pytest.skip(
            f"NebulaGraph graphd unreachable at {_NEBULA_HOST}:{_NEBULA_PORT}. "
            "Deploy it and run: "
            f"kubectl port-forward svc/nebula-graphd {_NEBULA_PORT}:9669"
        )

    config = NebulaConfig()
    config.max_connection_pool_size = 4
    config.timeout = 10000  # ms

    pool = ConnectionPool()
    ok = pool.init([( _NEBULA_HOST, _NEBULA_PORT )], config)
    if not ok:
        pytest.skip(
            f"NebulaGraph ConnectionPool.init failed for {_NEBULA_HOST}:{_NEBULA_PORT}"
        )

    try:
        session = pool.get_session(_NEBULA_USER, _NEBULA_PASSWORD)
    except Exception as exc:  # pragma: no cover - environment-dependent
        pool.close()
        pytest.skip(f"NebulaGraph authentication failed: {exc}")

    yield session

    try:
        session.release()
    except Exception:  # pragma: no cover - best-effort
        pass
    pool.close()


def _ngql(session, stmt: str):
    """Execute an nGQL statement, stripping a leading semicolon-free form."""
    resp = session.execute(stmt)
    if not resp.is_succeeded():
        pytest.fail(f"nGQL failed: {stmt!r} -> {resp.error_msg()}")
    return resp


def test_nebula_connection(nebula_session) -> None:
    """A live NebulaGraph session can execute a trivial nGQL query."""
    resp = nebula_session.execute("YIELD 1 AS one")
    assert resp.is_succeeded(), resp.error_msg()


def test_nebula_space_tag_edge_query(nebula_session) -> None:
    """End-to-end: create space, define schema, insert, and query the graph.

    Uses a per-run space name to avoid collisions across test invocations.
    """
    space = f"eptest_{uuid.uuid4().hex[:8]}"

    # Create and use a dedicated space.
    _ngql(nebula_session, f'CREATE SPACE IF NOT EXISTS `{space}` (vid_type=FIXED_STRING(64));')
    # Space creation is async; wait briefly for propagation.
    import time

    time.sleep(3)
    _ngql(nebula_session, f'USE `{space}`;')

    # Define a tag (vertex label) and an edge type.
    _ngql(nebula_session, 'CREATE TAG IF NOT EXISTS concept(name string);')
    _ngql(nebula_session, 'CREATE EDGE IF NOT EXISTS prerequisite();')
    # Schema changes need a moment to propagate before insertion.
    time.sleep(2)

    # Insert vertices.
    _ngql(nebula_session, 'INSERT VERTEX concept(name) VALUES "addition":("addition");')
    _ngql(nebula_session, 'INSERT VERTEX concept(name) VALUES "multiplication":("multiplication");')
    _ngql(nebula_session, 'INSERT VERTEX concept(name) VALUES "algebra":("algebra");')

    # Insert edges: addition -> multiplication -> algebra.
    _ngql(nebula_session, 'INSERT EDGE prerequisite() VALUES "addition"->"multiplication":();')
    _ngql(nebula_session, 'INSERT EDGE prerequisite() VALUES "multiplication"->"algebra":();')

    # 1-hop traversal from addition.
    resp = nebula_session.execute(
        'GO 1 STEP FROM "addition" OVER prerequisite YIELD $$.concept.name AS next'
    )
    assert resp.is_succeeded(), resp.error_msg()
    rows = resp.rows()
    next_names = {row.values[0].get_sVal().decode() for row in rows}
    assert "multiplication" in next_names

    # 2-hop traversal: addition -> multiplication -> algebra.
    resp2 = nebula_session.execute(
        'GO 2 STEPS FROM "addition" OVER prerequisite YIELD $$.concept.name AS next'
    )
    assert resp2.is_succeeded(), resp2.error_msg()
    rows2 = resp2.rows()
    two_hop_names = {row.values[0].get_sVal().decode() for row in rows2}
    assert "algebra" in two_hop_names

    # Cleanup: drop the test space.
    nebula_session.execute(f'DROP SPACE IF EXISTS `{space}`;')
