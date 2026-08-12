"""Agent state definition."""

from __future__ import annotations

from typing import TypedDict


class AgentState(TypedDict, total=False):
    """Hierarchical multi-agent state.

    Fields are optional (total=False) so subgraphs can return partial updates.
    """

    messages: list[dict]
    current_node: str
    current_agent: str  # active sub-agent name (e.g. "qa_agent")
    iteration: int
    tenant_id: str | None
    user_id: str | None
    session_id: str | None
    context: list[dict]
    memories: list[dict]  # long-term memory injected (D-045 ep-memory)
    reflections: list[str]  # Reflexion reflection history
    trial_count: int  # Reflexion retry counter
    artifacts: list[dict]  # agent-produced artifacts
    task_id: str | None  # A2A Task ID
    tool_calls: list[dict]  # tool call history
    # Internal: marks that a subgraph has completed so the supervisor ends the loop
    _round_done: bool
    # Reflexion evaluate output (transient)
    evaluation_passed: bool
    feedback: str
    final_answer: str | None
    error: str | None
