"""MemoryManager: orchestrates the four-level memory system."""

from __future__ import annotations

from .config import MemoryConfig
from .forgetting import ForgettingPolicy
from .l0_immediate import ImmediateMemory
from .l1_working import WorkingMemory
from .l2_longterm import LongTermMemory
from .l3_organizational import OrganizationalMemory
from .models import ContextWindow, Memory
from .procedural_store import ProceduralMemory
from .retriever import MemoryRetriever


class MemoryManager:
    """Top-level facade over L0-L3 memory stores."""

    def __init__(self, config: MemoryConfig | None = None) -> None:
        self.config = config or MemoryConfig()
        self.l0 = ImmediateMemory(self.config)
        self.l1 = WorkingMemory(self.config)
        self.l2 = LongTermMemory(self.config)
        self.l3 = OrganizationalMemory(self.config)
        self.procedural = ProceduralMemory(self.config)
        self.retriever = MemoryRetriever(self.l2, self.l3, self.procedural)
        self.forgetting = ForgettingPolicy(self.config, self.l2)

    async def add(
        self, messages: list[dict], tenant_id: str, user_id: str, session_id: str
    ) -> None:
        """Persist messages through the memory hierarchy.

        1. Append each message to L0 immediate memory.
        2. When L0 overflows, summarize evicted messages and store in L1.
        3. Asynchronously extract long-term memories into L2.
        """
        for msg in messages:
            overflow = await self.l0.add(msg, tenant_id, session_id)
            if overflow:
                summary = self.l0.summarize(overflow)
                existing = await self.l1.get_summary(tenant_id, user_id)
                combined = f"{existing} {summary}".strip() if existing else summary
                await self.l1.set_summary(combined, tenant_id, user_id)

        # L2 extraction from the supplied messages.
        await self.l2.add(messages, tenant_id=tenant_id, user_id=user_id)

    async def search(
        self, query: str, tenant_id: str, user_id: str, top_k: int = 5
    ) -> list[Memory]:
        """Search L2/L3 long-term memories via hybrid retrieval."""
        return await self.retriever.retrieve(
            query, tenant_id=tenant_id, user_id=user_id, top_k=top_k
        )

    async def get_context(
        self, tenant_id: str, user_id: str, session_id: str, query: str | None = None
    ) -> ContextWindow:
        """Aggregate L0+L1+L2+L3 into a ContextWindow."""
        immediate = await self.l0.get(tenant_id, session_id)
        summary = await self.l1.get_summary(tenant_id, user_id)
        long_term: list[Memory] = []
        org: list[Memory] = []
        if query:
            long_term = await self.l2.search(query, tenant_id, user_id, top_k=5)
            org = await self.l3.search(query, tenant_id, top_k=3)
        else:
            long_term = await self.l2.list_all(tenant_id, user_id)
            org = await self.l3.list_all(tenant_id)
        return ContextWindow(
            immediate_window=immediate,
            working_summary=summary,
            long_term_memories=long_term,
            org_memories=org,
        )

    async def forget(self, memory_id: str) -> None:
        """Delete a memory from L2 and L3."""
        await self.l2.delete(memory_id)
        try:
            await self.l3.delete(memory_id, is_admin=True)
        except PermissionError:
            pass

    async def promote_to_org(self, memory_id: str) -> Memory | None:
        """Promote an L2 individual memory to L3 organizational memory.

        In dev/CI mode (or when Mem0 is disabled), searches the in-memory L2
        fallback backend. In production with Mem0, searches Mem0 by id via
        ``l2.search`` with a content-agnostic query — callers should pass the
        ``Memory`` object directly via ``l3.promote()`` instead.
        """
        # Dev/CI path: search in-memory fallback by id.
        for mem in self.l2._fallback._docs:
            if mem.id == memory_id:
                return await self.l3.promote(mem)
        return None

    async def run_forgetting(self) -> dict:
        """Run a forgetting cycle over L2."""
        return await self.forgetting.run_forgetting_cycle()
