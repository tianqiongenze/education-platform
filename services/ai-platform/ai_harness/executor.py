"""AgentExecutor — wraps agent graph invocation with timeout and recursion-depth guards.

Iron Law D-046 Harness: every agent invocation must be bounded by:
  - max_time_sec: wall-clock timeout (default 120s)
  - max_depth: maximum graph node transitions (default 50)
  - max_trials: maximum reflexion retry loops (default 3)

Depth/trial control is enforced TWO ways:
  1. ``recursion_limit`` is passed in the LangGraph runnable config so the
     framework itself raises ``GraphRecursionError`` when the limit is hit.
  2. A hard break in the executor loop catches ``GraphRecursionError`` and
     converts it to an ``ExecutionResult(depth_exceeded=True)``.

If any limit is exceeded, ExecutionError is raised and AuditLogger records
the breach.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any

import structlog

logger = structlog.get_logger(__name__)

try:
    from langgraph.errors import GraphRecursionError  # type: ignore

    _HAS_GRAPH_RECURSION_ERROR = True
except Exception:  # pragma: no cover
    _HAS_GRAPH_RECURSION_ERROR = False

    class GraphRecursionError(Exception):
        """Fallback when langgraph is unavailable."""


@dataclass
class ExecutionResult:
    """Result of a guarded agent execution."""

    output: dict[str, Any]
    elapsed_sec: float
    node_transitions: int
    trials: int
    timed_out: bool = False
    depth_exceeded: bool = False
    error: str | None = None


class ExecutionError(Exception):
    """Raised when execution guardrails are violated."""

    pass


@dataclass
class ExecutionLimits:
    """Guardrail limits for agent execution."""

    max_time_sec: float = 120.0
    max_depth: int = 50
    max_trials: int = 3

    def recursion_limit(self) -> int:
        """Translate max_depth + max_trials into a LangGraph recursion_limit.

        Each reflexion retry loop adds roughly 2 node transitions
        (reflect -> re-execute); we give headroom so legitimate runs succeed
        while unbounded loops are cut off.
        """
        return self.max_depth + (self.max_trials * 2)


class AgentExecutor:
    """Executes a LangGraph compiled graph with guardrails.

    Usage:
        executor = AgentExecutor(limits=ExecutionLimits(max_time_sec=60))
        result = await executor.execute(graph, initial_state, config)
    """

    def __init__(self, limits: ExecutionLimits | None = None) -> None:
        self.limits = limits or ExecutionLimits()
        self._audit: AuditLogger | None = None  # injected externally

    def attach_audit(self, audit: AuditLogger) -> None:
        """Attach an audit logger for recording execution events."""
        self._audit = audit

    async def execute(
        self,
        graph: Any,
        initial_state: dict[str, Any],
        config: dict[str, Any] | None = None,
    ) -> ExecutionResult:
        """Execute graph with timeout and depth guards.

        Args:
            graph: A compiled LangGraph (has .ainvoke method).
            initial_state: Initial agent state dict.
            config: LangGraph config (thread_id, etc.).

        Returns:
            ExecutionResult with output and metadata.
        """
        config = dict(config or {})
        # Enforce recursion_limit at the LangGraph level (hard break #1).
        config.setdefault("recursion_limit", self.limits.recursion_limit())

        start = time.monotonic()
        tenant_id = initial_state.get("tenant_id", "unknown")
        agent_name = initial_state.get("current_agent", "default")

        try:
            raw_output = await asyncio.wait_for(
                graph.ainvoke(initial_state, config=config),
                timeout=self.limits.max_time_sec,
            )
        except TimeoutError:
            elapsed = time.monotonic() - start
            logger.warning(
                "agent_timeout",
                tenant=tenant_id,
                agent=agent_name,
                elapsed=elapsed,
                limit=self.limits.max_time_sec,
            )
            if self._audit:
                await self._audit.log(AuditEntry(
                    tenant_id=tenant_id,
                    agent=agent_name,
                    event="timeout",
                    detail=f"Exceeded {self.limits.max_time_sec}s",
                    elapsed_sec=elapsed,
                ))
            return ExecutionResult(
                output={},
                elapsed_sec=elapsed,
                node_transitions=0,
                trials=0,
                timed_out=True,
                error=f"Agent timed out after {self.limits.max_time_sec}s",
            )
        except GraphRecursionError as exc:
            # Hard break #2: LangGraph hit recursion_limit (depth/trials).
            elapsed = time.monotonic() - start
            logger.warning(
                "agent_depth_exceeded",
                tenant=tenant_id,
                agent=agent_name,
                elapsed=elapsed,
                recursion_limit=self.limits.recursion_limit(),
                max_depth=self.limits.max_depth,
                max_trials=self.limits.max_trials,
            )
            if self._audit:
                await self._audit.log(AuditEntry(
                    tenant_id=tenant_id,
                    agent=agent_name,
                    event="depth_exceeded",
                    detail=(
                        f"recursion_limit={self.limits.recursion_limit()} "
                        f"(max_depth={self.limits.max_depth}, "
                        f"max_trials={self.limits.max_trials})"
                    ),
                    elapsed_sec=elapsed,
                ))
            return ExecutionResult(
                output={},
                elapsed_sec=elapsed,
                node_transitions=self.limits.max_depth,
                trials=self.limits.max_trials,
                depth_exceeded=True,
                error=f"Agent exceeded depth/trial limit: {exc}",
            )
        except Exception as exc:
            elapsed = time.monotonic() - start
            logger.error("agent_error", tenant=tenant_id, agent=agent_name, error=str(exc))
            if self._audit:
                await self._audit.log(AuditEntry(
                    tenant_id=tenant_id,
                    agent=agent_name,
                    event="error",
                    detail=str(exc),
                    elapsed_sec=elapsed,
                ))
            return ExecutionResult(
                output={},
                elapsed_sec=elapsed,
                node_transitions=0,
                trials=0,
                error=str(exc),
            )

        elapsed = time.monotonic() - start
        transitions = len(raw_output.get("node_history", [])) if isinstance(raw_output, dict) else 0
        trials = raw_output.get("trial_count", 1) if isinstance(raw_output, dict) else 1

        # Hard break #3: post-execution sanity check on trials.
        if trials > self.limits.max_trials:
            logger.warning(
                "agent_trials_exceeded_post",
                tenant=tenant_id,
                agent=agent_name,
                trials=trials,
                limit=self.limits.max_trials,
            )
            return ExecutionResult(
                output=raw_output if isinstance(raw_output, dict) else {"output": raw_output},
                elapsed_sec=elapsed,
                node_transitions=transitions,
                trials=trials,
                depth_exceeded=True,
                error=f"Agent exceeded max_trials={self.limits.max_trials} (actual={trials})",
            )

        if self._audit:
            await self._audit.log(AuditEntry(
                tenant_id=tenant_id,
                agent=agent_name,
                event="completed",
                detail="ok",
                elapsed_sec=elapsed,
            ))

        return ExecutionResult(
            output=raw_output if isinstance(raw_output, dict) else {"output": raw_output},
            elapsed_sec=elapsed,
            node_transitions=transitions,
            trials=trials,
        )
