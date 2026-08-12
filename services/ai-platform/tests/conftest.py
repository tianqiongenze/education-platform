"""Shared pytest configuration for ai-platform tests.

This conftest deliberately does NOT set environment variables globally. Test
files that need a specific EP_DEV_ONLY / INDUSTRY_CONFIG_PATH setting set it
at the top of their module before importing ai-platform code (the established
pattern in test_rag.py and test_ep_memory.py). Setting it here would interfere
with tests that assert dev-only fallback behavior.

For the industry-agnostic config tests (test_industry_config.py), each test
manages EP_DEV_ONLY / INDUSTRY_CONFIG_PATH and the module-level cache itself.
"""

from __future__ import annotations
