"""Forgetting policies.

    L0: TTL 24h auto-expiry (handled by Redis TTL).
    L1: 7d no-access degradation (handled by Redis TTL).
    L2: 30d no-hit -> score *= decay_factor; 90d no-hit -> archive (cold store).
    L3: never auto-expires.
"""

from __future__ import annotations

import time

from .config import MemoryConfig
from .l2_longterm import LongTermMemory


class ForgettingPolicy:
    """Applies decay and archival to L2 long-term memories."""

    def __init__(self, config: MemoryConfig, l2: LongTermMemory) -> None:
        self.config = config
        self.l2 = l2
        self._archived: list = []

    async def run_forgetting_cycle(self, now: float | None = None) -> dict:
        """Run one forgetting cycle over L2 memories. Returns a summary
        dict with counts: {decayed, archived}."""
        now = now if now is not None else time.time()
        decayed = 0
        archived = 0
        decay_threshold = now - self.config.l2_decay_days * 86400
        archive_threshold = now - self.config.l2_archive_days * 86400

        # Operate on the in-memory fallback backend (production would query
        # Mem0/Milvus). The fallback owns the locally-extracted Memory objects.
        docs = self.l2._fallback._docs
        for mem in docs:
            last = mem.last_accessed_at
            if last <= archive_threshold:
                self._archived.append(mem)
                archived += 1
            elif last <= decay_threshold:
                mem.score *= self.config.l2_decay_factor
                decayed += 1

        # Remove archived memories from the active store.
        if archived:
            archived_ids = {m.id for m in self._archived}
            self.l2._fallback._docs = [
                m for m in docs if m.id not in archived_ids
            ]

        return {"decayed": decayed, "archived": archived}

    @property
    def archived(self) -> list:
        return list(self._archived)
