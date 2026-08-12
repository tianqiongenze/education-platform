"""Harness — agent execution guardrails.

Provides AgentExecutor (timeout/depth guards), SandboxedToolNode (permission+budget),
TokenBudget (tenant×agent×model), and AuditLogger (Redis Stream→Iceberg).
"""

from __future__ import annotations

from .executor import AgentExecutor, ExecutionResult, ExecutionError
from .sandbox import SandboxedToolNode, ToolPermission, ToolBudget
from .token_budget import TokenBudget, BudgetExceeded
from .audit import AuditLogger, AuditEntry

__all__ = [
    "AgentExecutor",
    "ExecutionResult",
    "ExecutionError",
    "SandboxedToolNode",
    "ToolPermission",
    "ToolBudget",
    "TokenBudget",
    "BudgetExceeded",
    "AuditLogger",
    "AuditEntry",
]
