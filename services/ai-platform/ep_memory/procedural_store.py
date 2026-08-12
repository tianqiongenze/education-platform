"""L2 Procedural Memory — TiDB recursive CTE skill graph (D-053).

Replaces the Neo4j graph traversal that was reserved (empty) in v1.0.
Procedural memory stores **skill graphs**: directed relationships between
learned competencies (e.g. "addition" → "multiplication" → "algebra").

Design decision (D-053):
  Neo4j Community Edition lacks clustering/HA (GPL v3, single point of
  failure), violating D-041 multi-active. For bounded-depth (≤5 hops)
  skill graph traversal, TiDB's recursive CTE is sufficient and adds zero
  new components — TiDB is already in the stack with native multi-active.

Iron Law 0 (D-052):
  * Production: TiDB connection failure → ``ConnectionError``.
  * Dev/CI (EP_DEV_ONLY=1): falls back to in-memory adjacency list.

Schema (created lazily on first connect):

  CREATE TABLE IF NOT EXISTS mem_procedural (
      id          VARCHAR(128) PRIMARY KEY,
      tenant_id   VARCHAR(64)  NOT NULL,
      skill_name  VARCHAR(256) NOT NULL,
      content     TEXT,
      metadata    JSON,
      INDEX idx_tenant (tenant_id),
      INDEX idx_skill  (tenant_id, skill_name)
  );

  CREATE TABLE IF NOT EXISTS mem_procedural_edges (
      source_id   VARCHAR(128) NOT NULL,
      target_id   VARCHAR(128) NOT NULL,
      tenant_id   VARCHAR(64)  NOT NULL,
      relation    VARCHAR(64)  DEFAULT 'prerequisite',
      weight      DOUBLE       DEFAULT 1.0,
      PRIMARY KEY (source_id, target_id),
      INDEX idx_tenant (tenant_id),
      INDEX idx_target (target_id)
  );

Recursive CTE query for skill graph traversal (≤5 hops):

  WITH RECURSIVE skill_path AS (
      -- Anchor: starting skill
      SELECT id, skill_name, 0 AS depth, CAST(id AS CHAR(2000)) AS path
      FROM mem_procedural
      WHERE tenant_id = %s AND skill_name = %s
      UNION ALL
      -- Recursive: follow edges
      SELECT e.target_id, p.skill_name, sp.depth + 1,
             CONCAT(sp.path, '->', e.target_id)
      FROM skill_path sp
      JOIN mem_procedural_edges e ON e.source_id = sp.id AND e.tenant_id = %s
      JOIN mem_procedural p ON p.id = e.target_id
      WHERE sp.depth < %s  -- max depth
  )
  SELECT DISTINCT id, skill_name, depth, path FROM skill_path ORDER BY depth;
"""

from __future__ import annotations

import os
import uuid
from typing import Any

import structlog

from .config import MemoryConfig
from .models import Memory, MemoryType

logger = structlog.get_logger(__name__)


def _is_dev_only() -> bool:
    return os.environ.get("EP_DEV_ONLY", "0") in ("1", "true", "True")


def _parse_tidb_url(url: str) -> tuple[str, int, str, str]:
    """Parse a TiDB connection URL into (host, port, database, user).

    Accepts the forms:
      * ``host:port``              -> ("host", port, "test", "root")
      * ``host:port/db``           -> ("host", port, "db",   "root")
      * ``user@host:port/db``      -> ("user", host, port, "db")
      * ``user:pass@host:port/db`` -> ("user", host, port, "db") (pass via env)
    Defaults mirror the live K8s deployment (root@tidb-cluster-tidb:4000/test).
    """
    user = os.environ.get("TIDB_USER", "root")
    password = os.environ.get("TIDB_PASSWORD", "")
    db = os.environ.get("TIDB_DB", "test")
    host = "localhost"
    port = 4000

    rest = url
    if "@" in rest:
        creds, rest = rest.split("@", 1)
        if ":" in creds:
            user, password = creds.split(":", 1)
        else:
            user = creds

    if "/" in rest:
        rest, db = rest.split("/", 1)

    if ":" in rest:
        host, port_s = rest.split(":", 1)
        port = int(port_s)
    else:
        host = rest or host

    # Stash password on the class-free module scope for the connect call.
    _TIDB_PASSWORD_CACHE["password"] = password
    return host, port, db, user


_TIDB_PASSWORD_CACHE: dict[str, str] = {}


class ProceduralMemory:
    """L2 procedural memory: skill graph backed by TiDB recursive CTE.

    In dev/CI mode (``EP_DEV_ONLY=1``) or when TiDB is unreachable,
    degrades to an in-memory adjacency list for tests.
    """

    def __init__(self, config: MemoryConfig) -> None:
        self.config = config
        self._conn: Any = None
        self._connected = False
        self._dev_nodes: dict[str, Memory] = {}
        self._dev_edges: list[tuple[str, str, str]] = []  # (source, target, relation)

    def _ensure_connected(self) -> bool:
        if self._connected:
            return True
        if not self.config.tidb_url:
            if _is_dev_only():
                logger.warning("ep_memory.procedural.no_tidb_url", dev_only=True)
                return False
            raise ConnectionError(
                "tidb_url not configured for procedural memory. "
                "Set EP_DEV_ONLY=1 for dev/CI fallback."
            )
        try:
            import pymysql  # type: ignore

            host, port, db, user = _parse_tidb_url(self.config.tidb_url)
            password = _TIDB_PASSWORD_CACHE.get("password", "")
            self._conn = pymysql.connect(
                host=host,
                port=port,
                database=db,
                user=user,
                password=password,
                autocommit=True,
            )
            self._ensure_schema()
            self._connected = True
            logger.info("ep_memory.procedural.connected", url=self.config.tidb_url)
            return True
        except Exception as exc:
            if _is_dev_only():
                logger.warning("ep_memory.procedural.fallback", error=str(exc), dev_only=True)
                return False
            logger.error("ep_memory.procedural.connection_failed", error=str(exc))
            raise ConnectionError(
                f"TiDB unreachable for procedural memory: {exc}. "
                "Set EP_DEV_ONLY=1 for dev/CI fallback."
            ) from exc

    def _ensure_schema(self) -> None:
        assert self._conn is not None
        table = self.config.procedural_table
        edges_table = f"{table}_edges"
        with self._conn.cursor() as cur:
            cur.execute(f"""
                CREATE TABLE IF NOT EXISTS {table} (
                    id          VARCHAR(128) PRIMARY KEY,
                    tenant_id   VARCHAR(64)  NOT NULL,
                    skill_name  VARCHAR(256) NOT NULL,
                    content     TEXT,
                    metadata    JSON,
                    INDEX idx_tenant (tenant_id),
                    INDEX idx_skill  (tenant_id, skill_name)
                )
            """)
            cur.execute(f"""
                CREATE TABLE IF NOT EXISTS {edges_table} (
                    source_id   VARCHAR(128) NOT NULL,
                    target_id   VARCHAR(128) NOT NULL,
                    tenant_id   VARCHAR(64)  NOT NULL,
                    relation    VARCHAR(64)  DEFAULT 'prerequisite',
                    weight      DOUBLE       DEFAULT 1.0,
                    PRIMARY KEY (source_id, target_id),
                    INDEX idx_tenant (tenant_id),
                    INDEX idx_target (target_id)
                )
            """)

    async def add_skill(
        self,
        tenant_id: str,
        skill_name: str,
        content: str = "",
        metadata: dict | None = None,
    ) -> Memory:
        """Add or update a skill node in the procedural graph."""
        skill_id = f"skill_{uuid.uuid4().hex[:12]}"
        mem = Memory(
            id=skill_id,
            tenant_id=tenant_id,
            content=content or skill_name,
            memory_type=MemoryType.PROCEDURAL,
            metadata={**(metadata or {}), "skill_name": skill_name},
        )

        if not self._ensure_connected():
            self._dev_nodes[skill_id] = mem
            return mem

        assert self._conn is not None
        table = self.config.procedural_table
        import json

        with self._conn.cursor() as cur:
            cur.execute(
                f"INSERT INTO {table} (id, tenant_id, skill_name, content, metadata) "
                "VALUES (%s, %s, %s, %s, %s)",
                (skill_id, tenant_id, skill_name, content, json.dumps(metadata or {})),
            )
        logger.info("ep_memory.procedural.skill_added", skill=skill_name, tenant=tenant_id)
        return mem

    async def add_edge(
        self,
        tenant_id: str,
        source_skill: str,
        target_skill: str,
        relation: str = "prerequisite",
    ) -> bool:
        """Add a directed edge between two skills (e.g. prerequisite → advanced)."""
        if not self._ensure_connected():
            # Find node IDs in dev mode.
            src_id = self._find_dev_node(tenant_id, source_skill)
            tgt_id = self._find_dev_node(tenant_id, target_skill)
            if src_id and tgt_id:
                self._dev_edges.append((src_id, tgt_id, relation))
                return True
            return False

        assert self._conn is not None
        table = self.config.procedural_table
        edges_table = f"{table}_edges"
        with self._conn.cursor() as cur:
            cur.execute(
                f"INSERT INTO {edges_table} (source_id, target_id, tenant_id, relation) "
                "SELECT s.id, t.id, %s, %s FROM "
                f"{table} s, {table} t WHERE s.skill_name=%s AND s.tenant_id=%s "
                "AND t.skill_name=%s AND t.tenant_id=%s",
                (tenant_id, relation, source_skill, tenant_id, target_skill, tenant_id),
            )
        return True

    async def traverse(
        self,
        tenant_id: str,
        start_skill: str,
        max_depth: int | None = None,
    ) -> list[dict]:
        """Traverse the skill graph from ``start_skill`` using recursive CTE.

        Returns a list of ``{id, skill_name, depth, path}`` dicts.
        Bounded to ``procedural_max_depth`` (default 5) to keep queries fast.
        """
        depth = max_depth or self.config.procedural_max_depth

        if not self._ensure_connected():
            return self._dev_traverse(tenant_id, start_skill, depth)

        assert self._conn is not None
        table = self.config.procedural_table
        edges_table = f"{table}_edges"
        sql = f"""
            WITH RECURSIVE skill_path AS (
                SELECT id, skill_name, 0 AS depth, CAST(id AS CHAR(2000)) AS path
                FROM {table}
                WHERE tenant_id = %s AND skill_name = %s
                UNION ALL
                SELECT e.target_id, p.skill_name, sp.depth + 1,
                       CONCAT(sp.path, '->', e.target_id)
                FROM skill_path sp
                JOIN {edges_table} e ON e.source_id = sp.id AND e.tenant_id = %s
                JOIN {table} p ON p.id = e.target_id
                WHERE sp.depth < %s
            )
            SELECT DISTINCT id, skill_name, depth, path FROM skill_path ORDER BY depth
        """
        with self._conn.cursor() as cur:
            cur.execute(sql, (tenant_id, start_skill, tenant_id, depth))
            rows = cur.fetchall()
        results: list[dict] = []
        for row in rows:
            results.append(
                {"id": row[0], "skill_name": row[1], "depth": row[2], "path": row[3]}
            )
        return results

    async def find_path(
        self, tenant_id: str, from_skill: str, to_skill: str
    ) -> list[str] | None:
        """Find shortest skill path between two skills. Returns skill names or None."""
        paths = await self.traverse(tenant_id, from_skill)
        # BFS already gives us shortest path first.
        for p in paths:
            if p["skill_name"] == to_skill:
                # Extract skill names from path string.
                return self._extract_skill_names(p["path"], tenant_id)
        return None

    def _find_dev_node(self, tenant_id: str, skill_name: str) -> str | None:
        for nid, mem in self._dev_nodes.items():
            if mem.tenant_id == tenant_id and mem.metadata.get("skill_name") == skill_name:
                return nid
        return None

    def _dev_traverse(self, tenant_id: str, start_skill: str, max_depth: int) -> list[dict]:
        """In-memory BFS for dev mode."""
        start_id = self._find_dev_node(tenant_id, start_skill)
        if not start_id:
            return []
        visited: set[str] = set()
        queue: list[tuple[str, int, str]] = [(start_id, 0, start_id)]
        results: list[dict] = []
        while queue:
            node_id, depth, path = queue.pop(0)
            if node_id in visited or depth > max_depth:
                continue
            visited.add(node_id)
            mem = self._dev_nodes.get(node_id)
            results.append(
                {
                    "id": node_id,
                    "skill_name": mem.metadata.get("skill_name", "") if mem else "",
                    "depth": depth,
                    "path": path,
                }
            )
            for src, tgt, _rel in self._dev_edges:
                if src == node_id and tgt not in visited:
                    queue.append((tgt, depth + 1, f"{path}->{tgt}"))
        return results

    def _extract_skill_names(self, path_str: str, tenant_id: str) -> list[str]:
        """Extract skill names from a path of node IDs.

        In dev mode, node IDs map to skill names via ``_dev_nodes``. In
        TiDB mode, ``traverse`` already returns the path as a chain of
        node IDs, so we resolve each ID back to its ``skill_name`` via a
        single batched SQL query.
        """
        node_ids = path_str.split("->")
        if self._dev_nodes:
            # Dev mode: look up in the in-memory map.
            return [
                self._dev_nodes.get(nid, None).metadata.get("skill_name", "")
                if self._dev_nodes.get(nid)
                else nid
                for nid in node_ids
            ]

        # TiDB mode: resolve node IDs -> skill names from the procedural table.
        if not self._connected or not self._conn:
            return node_ids
        table = self.config.procedural_table
        if not node_ids:
            return []
        placeholders = ",".join(["%s"] * len(node_ids))
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT id, skill_name FROM {table} "
                f"WHERE id IN ({placeholders})",
                tuple(node_ids),
            )
            id_to_name = {row[0]: row[1] for row in cur.fetchall()}
        return [id_to_name.get(nid, nid) for nid in node_ids]
