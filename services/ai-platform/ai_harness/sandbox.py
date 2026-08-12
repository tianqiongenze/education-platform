"""SandboxedToolNode — permission-gated, budget-bounded tool execution for LangGraph.

Wraps MCP tools and custom tools with:
  - Permission check (tool must be in agent's allowed_tools list)
  - Budget check (tenant×agent token/cost budget)
  - Input sanitization (block prompt injection, path traversal)
  - Audit logging (every tool call recorded)
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Any

import structlog

logger = structlog.get_logger(__name__)

# Suspicious patterns for prompt injection / path traversal
_SUSPICIOUS_PATTERNS = [
    re.compile(r"(?i)ignore\s+(previous|above)\s+instructions"),
    re.compile(r"(?i)system\s*:\s*you\s+are"),
    re.compile(r"\.\./\.\./"),  # path traversal
    re.compile(r"(?i)rm\s+-rf\s+/"),
    re.compile(r"(?i)DROP\s+TABLE"),
    re.compile(r"<script[^>]*>", re.IGNORECASE),
]


@dataclass
class ToolPermission:
    """Permission definition for a tool."""

    tool_name: str
    allowed_args: list[str] = field(default_factory=list)
    max_output_tokens: int = 4096
    requires_approval: bool = False
    rate_limit_per_min: int = 60


@dataclass
class ToolBudget:
    """Token/cost budget for a tenant×agent×model triple."""

    tenant_id: str
    agent: str
    model: str
    max_tokens: int = 100000
    consumed_tokens: int = 0
    max_cost_cents: int = 500  # $5.00 per tenant per agent per day
    consumed_cost_cents: int = 0

    def can_spend(self, tokens: int, cost_cents: int = 0) -> bool:
        return (
            self.consumed_tokens + tokens <= self.max_tokens
            and self.consumed_cost_cents + cost_cents <= self.max_cost_cents
        )

    def spend(self, tokens: int, cost_cents: int = 0) -> None:
        self.consumed_tokens += tokens
        self.consumed_cost_cents += cost_cents


class BudgetExceeded(Exception):
    """Raised when token or cost budget is exceeded."""

    pass


class PermissionDenied(Exception):
    """Raised when tool is not in agent's allowed list."""

    pass


class InputBlocked(Exception):
    """Raised when input contains suspicious patterns."""

    pass


def sanitize_input(text: str) -> str:
    """Check input for suspicious patterns. Returns clean text or raises InputBlocked."""
    for pattern in _SUSPICIOUS_PATTERNS:
        if pattern.search(text):
            raise InputBlocked(f"Input blocked by sandbox: matched pattern {pattern.pattern[:40]}")
    return text


class SandboxedToolNode:
    """A LangGraph-compatible tool node that enforces permissions, budget, and sanitization.

    Usage in a LangGraph:
        sandbox = SandboxedToolNode(perms=[...], budgets={...})
        graph.add_node("tools", sandbox.execute)
    """

    def __init__(
        self,
        perms: list[ToolPermission] | None = None,
        budgets: dict[str, ToolBudget] | None = None,
        audit: Any = None,
    ) -> None:
        self._perms: dict[str, ToolPermission] = {p.tool_name: p for p in (perms or [])}
        self._budgets: dict[str, ToolBudget] = budgets or {}
        self._audit = audit
        self._tool_registry: dict[str, Any] = {}  # tool_name → callable

    def register_tool(self, name: str, func: Any) -> None:
        """Register a callable tool."""
        self._tool_registry[name] = func

    async def execute(self, state: dict[str, Any]) -> dict[str, Any]:
        """Execute a tool call from agent state.

        Expects state to contain:
          - tool_calls: list[{name, args, id}]
          - tenant_id, current_agent
        Returns:
          dict with tool_results: list[{tool, output, error}]
        """
        tool_calls = state.get("tool_calls", [])
        tenant_id = state.get("tenant_id", "unknown")
        agent = state.get("current_agent", "default")
        results: list[dict[str, Any]] = []

        for call in tool_calls:
            tool_name = call.get("name", "")
            args = call.get("args", {})

            # 1. Permission check
            if tool_name not in self._perms:
                logger.warning("permission_denied", tool=tool_name, agent=agent, tenant=tenant_id)
                results.append({
                    "tool": tool_name,
                    "output": None,
                    "error": f"Permission denied: tool '{tool_name}' not allowed for agent '{agent}'",
                })
                continue

            perm = self._perms[tool_name]

            # 2. Input sanitization
            try:
                for key, val in args.items():
                    if isinstance(val, str):
                        args[key] = sanitize_input(val)
            except InputBlocked as e:
                results.append({"tool": tool_name, "output": None, "error": str(e)})
                if self._audit:
                    await self._audit.log_tool(tenant_id, agent, tool_name, "blocked", str(e))
                continue

            # 3. Budget check
            budget_key = f"{tenant_id}:{agent}"
            budget = self._budgets.get(budget_key)
            if budget and not budget.can_spend(tokens=perm.max_output_tokens):
                raise BudgetExceeded(
                    f"Budget exceeded for {budget_key}: "
                    f"{budget.consumed_tokens}/{budget.max_tokens} tokens used"
                )

            # 4. Execute
            func = self._tool_registry.get(tool_name)
            if not func:
                results.append({"tool": tool_name, "output": None, "error": f"Tool '{tool_name}' not registered"})
                continue

            try:
                import asyncio

                if asyncio.iscoroutinefunction(func):
                    output = await func(**args)
                else:
                    output = func(**args)

                # Truncate output if exceeds max_output_tokens
                output_str = str(output)
                if len(output_str) > perm.max_output_tokens:
                    output_str = output_str[: perm.max_output_tokens] + "...[truncated]"

                if budget:
                    budget.spend(tokens=len(output_str) // 4)  # rough token estimate

                results.append({"tool": tool_name, "output": output_str, "error": None})

                if self._audit:
                    await self._audit.log_tool(tenant_id, agent, tool_name, "success", "")

            except Exception as e:
                results.append({"tool": tool_name, "output": None, "error": str(e)})
                if self._audit:
                    await self._audit.log_tool(tenant_id, agent, tool_name, "error", str(e))

        return {"tool_results": results}
