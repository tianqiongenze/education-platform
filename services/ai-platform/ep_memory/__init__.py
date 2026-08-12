"""ep-memory: multi-level intelligent memory system (D-045).

Four-level architecture:
    L0 Immediate  — Redis (mem:imm:{tenant}:{session}), last 10 turns, TTL 24h
    L1 Working    — Redis (mem:work:{tenant}:{user}), session summary + task ctx, TTL 7d
    L2 Long-term  — Mem0 wrapper (episodic->Milvus, semantic->TiDB, procedural->Neo4j)
    L3 Organizational — Milvus (mem_org_{tenant}), tenant-level shared memory
"""

from __future__ import annotations

from .config import MemoryConfig, config
from .l0_immediate import ImmediateMemory
from .l1_working import WorkingMemory
from .l2_longterm import LongTermMemory
from .l3_organizational import OrganizationalMemory
from .manager import MemoryManager
from .models import ContextWindow, Memory, MemoryType, MessageRole

__all__ = [
    "MemoryConfig",
    "config",
    "ImmediateMemory",
    "WorkingMemory",
    "LongTermMemory",
    "OrganizationalMemory",
    "MemoryManager",
    "ContextWindow",
    "Memory",
    "MemoryType",
    "MessageRole",
]
