"""Memory extractor.

Extracts memory candidates from raw conversation messages and classifies them
into episodic / semantic / procedural. Currently uses simple heuristic/mock
extraction; in production this hooks into the Mem0 add pipeline which uses an
LLM to extract salient facts.
"""

from __future__ import annotations

import re

from .models import Memory, MemoryType

# Simple classification heuristics.
_PROCEDURAL_RE = re.compile(r"\b(step|how to|procedure|first|then|finally)\b", re.IGNORECASE)
_SEMANTIC_RE = re.compile(r"\b(is|are|means|fact|definition|rule)\b", re.IGNORECASE)


class MemoryExtractor:
    """Extract memory candidates from conversation messages."""

    def extract_memories(
        self, messages: list[dict], tenant_id: str = "", user_id: str = ""
    ) -> list[Memory]:
        """Extract memory candidates. Mock implementation: one Memory per
        non-empty user/assistant message."""
        memories: list[Memory] = []
        for msg in messages:
            role = msg.get("role", "user")
            content = (msg.get("content") or "").strip()
            if not content:
                continue
            mtype = self.classify_memory(content)
            memories.append(
                Memory(
                    tenant_id=tenant_id,
                    user_id=user_id,
                    content=f"[{role}] {content}",
                    memory_type=mtype,
                    metadata={"source_role": role},
                )
            )
        return memories

    @staticmethod
    def classify_memory(content: str) -> MemoryType:
        """Classify a piece of content into a memory type using heuristics."""
        if _PROCEDURAL_RE.search(content):
            return MemoryType.PROCEDURAL
        if _SEMANTIC_RE.search(content):
            return MemoryType.SEMANTIC
        return MemoryType.EPISODIC
