"""JSON Schema validation and sanitization helpers for MCP tools.

These utilities guard the host agent from two classes of risk:
1. Malformed tool arguments (structural validation against a JSON Schema).
2. Prompt-injection via malicious tool descriptions (sanitization).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

# A conservative JSON-Schema dialect covering what MCP tool schemas use.
_SIMPLE_TYPES = {"string", "number", "integer", "boolean", "array", "object", "null"}

# Patterns that commonly indicate prompt-injection attempts inside tool
# descriptions. We strip these rather than reject the description outright,
# so a misbehaving plugin degrades gracefully instead of breaking discovery.
_INJECTION_PATTERNS = [
    re.compile(r"ignore\s+(all\s+)?previous\s+instructions", re.IGNORECASE),
    re.compile(r"you\s+are\s+(now|an?)\s+", re.IGNORECASE),
    re.compile(r"<\s*system\s*>.*?<\s*/\s*system\s*>", re.IGNORECASE | re.DOTALL),
    re.compile(r"<\s*prompt\s*>.*?<\s*/\s*prompt\s*>", re.IGNORECASE | re.DOTALL),
    re.compile(r"system\s*prompt\s*:", re.IGNORECASE),
    re.compile(r"reveal\s+(your|the)\s+(system|hidden)\s+prompt", re.IGNORECASE),
]


@dataclass
class ToolSchema:
    """Schema descriptor for an MCP tool.

    Attributes:
        name: Tool identifier, unique within a server.
        description: Human-readable description used by the LLM for tool selection.
        input_schema: JSON Schema for the tool's input arguments.
        output_schema: Optional JSON Schema describing the tool's output.
    """

    name: str
    description: str
    input_schema: dict[str, Any] = field(default_factory=lambda: {"type": "object", "properties": {}})
    output_schema: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "name": self.name,
            "description": self.description,
            "input_schema": self.input_schema,
        }
        if self.output_schema is not None:
            d["output_schema"] = self.output_schema
        return d

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ToolSchema:
        return cls(
            name=data["name"],
            description=data.get("description", ""),
            input_schema=data.get("input_schema", {"type": "object", "properties": {}}),
            output_schema=data.get("output_schema"),
        )


def sanitize_tool_description(desc: str) -> str:
    """Strip prompt-injection patterns from a tool description.

    Args:
        desc: Raw tool description, possibly originating from an untrusted plugin.

    Returns:
        A sanitized description with injection patterns replaced by a placeholder.
    """
    if not desc:
        return ""
    cleaned = desc
    for pattern in _INJECTION_PATTERNS:
        cleaned = pattern.sub("[filtered]", cleaned)
    # Truncate to a sane bound so a plugin cannot exhaust the context window
    # with a gigantic description.
    return cleaned[:2000]


def _check_type(value: Any, expected: str) -> bool:
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "null":
        return value is None
    if expected == "array":
        return isinstance(value, list)
    if expected == "object":
        return isinstance(value, dict)
    return True  # Unknown type: be permissive


def _validate_against_schema(value: Any, schema: dict[str, Any], depth: int = 0) -> bool:
    """Recursively validate ``value`` against a JSON-Schema-like ``schema``.

    Only the subset of JSON Schema used by MCP tool schemas is supported:
    ``type``, ``properties``, ``required``, ``items``, ``enum``.
    """
    if depth > 10:  # Defense against deeply nested schemas.
        return False
    if not isinstance(schema, dict):
        return True

    expected_type = schema.get("type")
    if isinstance(expected_type, list):
        if not any(_check_type(value, t) for t in expected_type):
            return False
    elif isinstance(expected_type, str):
        if not _check_type(value, expected_type):
            return False

    if "enum" in schema and value not in schema["enum"]:
        return False

    if isinstance(value, dict) and expected_type in (None, "object"):
        properties = schema.get("properties", {})
        for req in schema.get("required", []):
            if req not in value:
                return False
        for key, val in value.items():
            if key in properties and not _validate_against_schema(val, properties[key], depth + 1):
                return False

    if isinstance(value, list) and expected_type in (None, "array"):
        items_schema = schema.get("items")
        if isinstance(items_schema, dict):
            for item in value:
                if not _validate_against_schema(item, items_schema, depth + 1):
                    return False

    return True


def validate_tool_args(schema: dict[str, Any], arguments: dict[str, Any]) -> bool:
    """Validate ``arguments`` against an MCP tool input ``schema``.

    Args:
        schema: JSON Schema describing the tool input (top-level object).
        arguments: The arguments to validate.

    Returns:
        True if ``arguments`` conforms to ``schema``; False otherwise.
    """
    if not isinstance(arguments, dict):
        return False
    if not isinstance(schema, dict):
        return True
    # Top-level should be an object schema.
    top_schema = dict(schema)
    top_schema.setdefault("type", "object")
    return _validate_against_schema(arguments, top_schema)
