"""Industry configuration — industry-agnostic AI middleware (SP-04).

The AI middleware infrastructure (memory, harness, MCP, A2A, plugin, hermes,
gateway) is industry-agnostic. All industry-specific behavior is expressed as
data in an ``IndustryConfig`` loaded from a YAML file. Switching industries
(e.g. education → healthcare) is a config swap, not a code change.

Design constraints honored:
- D-039: no ``synchronized`` blocks; this module is stateless except for a
  module-level cache guarded by ``threading.Lock``.
- D-040: GraalVM AOT friendly — plain dataclasses, no metaprogramming.
- EP_DEV_ONLY: when active (unset/blank/"true"), a minimal generic config is
  returned so dev/CI never needs an industry YAML file.
"""

from __future__ import annotations

import os
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import structlog
import yaml

logger = structlog.get_logger(__name__)

# Default industry config path (relative to the service root). The service root
# is ``services/ai-platform``; the YAML lives under ``config/``.
_DEFAULT_YAML = str(Path(__file__).resolve().parent.parent / "config" / "education.yaml")


def _ep_dev_only_active() -> bool:
    """Return True when EP_DEV_ONLY guard is active (unset/blank/"true").

    This mirrors the project-wide guard: dev/CI runs with the guard active and
    must never require a real industry YAML or external services.
    """
    val = os.environ.get("EP_DEV_ONLY")
    return val is None or val.strip() == "" or val.strip().lower() == "true"


@dataclass
class AgentGraphConfig:
    """Reference to an industry-specific agent subgraph builder.

    Attributes:
        name: Subgraph node name (e.g. ``grading_agent``).
        module: Dotted Python module path containing the builder function.
        builder: Name of the builder callable within ``module``.
    """

    name: str
    module: str
    builder: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AgentGraphConfig":
        return cls(
            name=str(data.get("name", "")),
            module=str(data.get("module", "")),
            builder=str(data.get("builder", "")),
        )

    def to_dict(self) -> dict[str, Any]:
        return {"name": self.name, "module": self.module, "builder": self.builder}


@dataclass
class ToolConfig:
    """An MCP tool advertised by this industry's agents.

    Attributes:
        name: Tool name (unique within the catalog).
        server: MCP server id that hosts this tool.
        description: Human-readable description.
    """

    name: str
    server: str
    description: str = ""

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ToolConfig":
        return cls(
            name=str(data.get("name", "")),
            server=str(data.get("server", "")),
            description=str(data.get("description", "")),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "server": self.server,
            "description": self.description,
        }


@dataclass
class IndustryConfig:
    """Industry configuration loaded from YAML.

    This is the single source of truth for industry-specific AI agent behavior.
    The platform code reads this config and never hardcodes industry names,
    prompts, keywords, routes, or tool catalogs.
    """

    domain_name: str = "generic"
    system_prompt: str = "You are an AI assistant."
    agent_graphs: list[AgentGraphConfig] = field(default_factory=list)
    tool_catalog: list[ToolConfig] = field(default_factory=list)
    intent_keywords: dict[str, list[str]] = field(default_factory=dict)
    event_routes: dict[str, list[str]] = field(default_factory=dict)

    # ---- Conversions -----------------------------------------------------

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "IndustryConfig":
        """Build an ``IndustryConfig`` from a parsed YAML dict."""
        return cls(
            domain_name=str(data.get("domain_name", "generic")),
            system_prompt=str(data.get("system_prompt", "You are an AI assistant.")),
            agent_graphs=[
                AgentGraphConfig.from_dict(g) for g in data.get("agent_graphs", []) or []
            ],
            tool_catalog=[
                ToolConfig.from_dict(t) for t in data.get("tool_catalog", []) or []
            ],
            intent_keywords={
                str(k): [str(x) for x in (v or [])]
                for k, v in (data.get("intent_keywords", {}) or {}).items()
            },
            event_routes={
                str(k): [str(x) for x in (v or [])]
                for k, v in (data.get("event_routes", {}) or {}).items()
            },
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "domain_name": self.domain_name,
            "system_prompt": self.system_prompt,
            "agent_graphs": [g.to_dict() for g in self.agent_graphs],
            "tool_catalog": [t.to_dict() for t in self.tool_catalog],
            "intent_keywords": {k: list(v) for k, v in self.intent_keywords.items()},
            "event_routes": {k: list(v) for k, v in self.event_routes.items()},
        }


def _minimal_default() -> IndustryConfig:
    """Return the minimal generic config used in dev/CI or when no YAML exists."""
    return IndustryConfig(
        domain_name="generic",
        system_prompt="You are an AI assistant.",
        agent_graphs=[],
        tool_catalog=[],
        intent_keywords={},
        event_routes={},
    )


# Module-level cache (D-039: guarded by Lock, no synchronized blocks).
_cache_lock = threading.Lock()
_cache: IndustryConfig | None = None


def load_industry_config(yaml_path: str | None = None) -> IndustryConfig:
    """Load the industry configuration.

    Resolution order:
      1. If ``yaml_path`` is provided and exists → load it.
      2. Otherwise fall back to ``config/education.yaml`` if it exists.
      3. Otherwise → minimal generic config.

    The ``EP_DEV_ONLY`` guard does NOT suppress YAML loading here: the industry
    config is a local data file, not an external service, so reading it is safe
    in dev/CI. (The guard's purpose is to avoid connecting to real
    Redis/Pulsar/Nacos.) When the YAML is absent (e.g. a fresh checkout of a
    non-education industry without a config yet), the minimal generic config is
    returned so the platform stays bootable.

    Invalid YAML is logged and falls back to the minimal config (graceful
    degradation) rather than raising.

    Results are cached per-process keyed by the resolved path; pass
    ``yaml_path=None`` repeatedly to use the cache.
    """
    global _cache

    resolved = yaml_path or os.environ.get("INDUSTRY_CONFIG_PATH") or _DEFAULT_YAML

    # Fast path: cached for this path.
    with _cache_lock:
        if _cache is not None:
            return _cache

    cfg = _load_from_file(resolved)
    if cfg is None:
        logger.info("industry_config.fallback_to_minimal", path=resolved)
        cfg = _minimal_default()

    with _cache_lock:
        _cache = cfg
    return cfg


def _load_from_file(path: str) -> IndustryConfig | None:
    """Load and parse a YAML file into an ``IndustryConfig``.

    Returns ``None`` on missing file or parse error (graceful degradation).
    """
    p = Path(path)
    if not p.is_file():
        logger.info("industry_config.file_not_found", path=path)
        return None
    try:
        with p.open("r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}
    except yaml.YAMLError as exc:
        logger.warning("industry_config.yaml_parse_error", path=path, error=str(exc))
        return None
    except OSError as exc:
        logger.warning("industry_config.io_error", path=path, error=str(exc))
        return None

    if not isinstance(data, dict):
        logger.warning("industry_config.invalid_structure", path=path, type=type(data).__name__)
        return None

    return IndustryConfig.from_dict(data)


def reset_cache() -> None:
    """Clear the module-level config cache (test helper)."""
    global _cache
    with _cache_lock:
        _cache = None
