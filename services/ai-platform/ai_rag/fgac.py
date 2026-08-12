"""Fine-Grained Access Control for multi-tenant RAG."""

from __future__ import annotations

import json


class FGACManager:
    """Manages per-tenant access policies for document metadata tags."""

    def __init__(self) -> None:
        self.tenant_policies: dict[str, set[str]] = {}

    def register_tenant(self, tenant_id: str, allowed_tags: list[str]) -> None:
        self.tenant_policies[tenant_id] = set(allowed_tags)

    def build_filter_expr(self, tenant_id: str, extra_filters: dict | None = None) -> str:
        parts: list[str] = [f'tenant_id == "{tenant_id}"']
        tags = self.tenant_policies.get(tenant_id)
        if tags:
            tags_json = json.dumps(sorted(tags))
            parts.append(f"tag in {tags_json}")
        if extra_filters:
            for key, value in extra_filters.items():
                if isinstance(value, str):
                    parts.append(f'{key} == "{value}"')
                elif isinstance(value, list):
                    parts.append(f"{key} in {json.dumps(value)}")
                else:
                    parts.append(f"{key} == {value}")
        return " && ".join(parts)

    def check_access(self, tenant_id: str, doc_metadata: dict) -> bool:
        allowed = self.tenant_policies.get(tenant_id)
        if not allowed:
            return False
        doc_tags = set(doc_metadata.get("tags", []) or [])
        if not doc_tags:
            # documents with no tags are accessible only if tenant allows "public"
            return "public" in allowed
        return bool(doc_tags & allowed)
