"""Conversation memory.

Backward-compatible thin wrapper that delegates to the D-045 multi-level
``ep_memory.MemoryManager``. The legacy synchronous ``add`` / ``get`` / ``clear``
API is preserved so existing callers (and tests) keep working; new code should
prefer the underlying ``MemoryManager`` for the full L0-L3 feature set.

Decision (iron rule 8, three options compared):
  A) Replace ConversationMemory entirely with MemoryManager      — breaks 108 tests (sync API).
  B) Keep ConversationMemory as-is, ignore ep_memory             — violates D-045 mandate.
  C) ConversationMemory delegates to MemoryManager + sync shim   — chosen: backward compatible + D-045.
"""

from __future__ import annotations

import asyncio
from collections import defaultdict
from typing import Any

from ep_memory import MemoryManager
from ep_memory.config import MemoryConfig


class ConversationMemory:
    """L0 conversation memory backed by ``ep_memory.MemoryManager``.

    The synchronous ``add`` / ``get`` / ``clear`` methods remain for backward
    compatibility; they operate on the L0 immediate store of a shared
    ``MemoryManager`` instance. Async callers should use ``amemory`` to access
    the full L0-L3 manager directly.
    """

    def __init__(self, max_history: int = 20, manager: MemoryManager | None = None) -> None:
        self.max_history = max_history
        # Build a MemoryManager sized to the requested history window. When no
        # external backends are configured (tests/dev) it degrades to in-memory.
        if manager is not None:
            self._manager = manager
        else:
            cfg = MemoryConfig.load()
            cfg.l0_max_messages = max_history
            self._manager = MemoryManager(cfg)
        # Preserve a tiny fallback dict so the legacy sync API is robust even
        # if the manager's L0 store is unavailable for some reason.
        self._legacy: dict[str, list[dict]] = defaultdict(list)

    @property
    def amemory(self) -> MemoryManager:
        """Access the underlying async ``MemoryManager`` (L0-L3)."""
        return self._manager

    # --- legacy synchronous API (delegates to L0 via a running loop) ----------

    @staticmethod
    def _run(coro: Any) -> Any:
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():  # we are inside an async context; run in new thread
                import threading

                result: dict[str, Any] = {}

                def _worker() -> None:
                    new_loop = asyncio.new_event_loop()
                    try:
                        result["value"] = new_loop.run_until_complete(coro)
                    finally:
                        new_loop.close()

                t = threading.Thread(target=_worker)
                t.start()
                t.join()
                return result.get("value")
            return loop.run_until_complete(coro)
        except RuntimeError:
            return asyncio.run(coro)

    def add(self, session_id: str, role: str, content: str) -> None:
        self._legacy[session_id].append({"role": role, "content": content})
        if len(self._legacy[session_id]) > self.max_history:
            self._legacy[session_id] = self._legacy[session_id][-self.max_history :]
        # Best-effort push into the L0 store. Errors are swallowed so the sync
        # API never raises due to memory-system issues.
        try:
            self._run(
                self._manager.l0.add(
                    {"role": role, "content": content},
                    tenant_id="default",
                    session_id=session_id,
                )
            )
        except Exception:
            pass

    def get(self, session_id: str) -> list[dict]:
        try:
            msgs = self._run(self._manager.l0.get("default", session_id))
            if msgs:
                return list(msgs)
        except Exception:
            pass
        return list(self._legacy.get(session_id, []))

    def clear(self, session_id: str) -> None:
        if session_id in self._legacy:
            del self._legacy[session_id]
        try:
            self._run(self._manager.l0.clear("default", session_id))
        except Exception:
            pass
