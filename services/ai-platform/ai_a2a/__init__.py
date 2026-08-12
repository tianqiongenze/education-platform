"""A2A protocol layer (Google 2025).

Exposes AgentCard, Task, Message/Part/Artifact models, A2AClient and A2AServer.
"""

from __future__ import annotations

from .agent_card import AgentCard, Skill
from .client import A2AClient
from .message import Artifact, FilePart, DataPart, Message, Part, TextPart
from .server import A2AServer, create_a2a_router
from .task import Task, TaskManager, TaskStatus
from .registry import AgentRegistry

__all__ = [
    "AgentCard",
    "Skill",
    "A2AClient",
    "A2AServer",
    "create_a2a_router",
    "Message",
    "Part",
    "TextPart",
    "FilePart",
    "DataPart",
    "Artifact",
    "Task",
    "TaskStatus",
    "TaskManager",
    "AgentRegistry",
]
