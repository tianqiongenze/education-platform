"""Tests for ep_common.config (D-110)."""

from __future__ import annotations

import pytest

from ep_common import config
from ep_common.config import (
    MissingEnvironmentVariableError,
    get_bool,
    get_env,
    get_int,
    get_list,
)


@pytest.fixture
def clean_env(monkeypatch):
    """Strip all keys these tests touch so cases are deterministic."""
    keys = [
        "EP_TEST_STR", "EP_TEST_INT", "EP_TEST_BOOL", "EP_TEST_LIST",
        "EP_TEST_REQUIRED", "EP_TEST_STRIPPED",
    ]
    for k in keys:
        monkeypatch.delenv(k, raising=False)
    return monkeypatch


def test_get_env_returns_value_when_set(clean_env):
    clean_env.setenv("EP_TEST_STR", "hello")
    assert get_env("EP_TEST_STR") == "hello"


def test_get_env_returns_default_when_missing(clean_env):
    assert get_env("EP_TEST_STR", default="fallback") == "fallback"


def test_get_env_returns_none_when_missing_and_no_default(clean_env):
    assert get_env("EP_TEST_STR") is None


def test_get_env_strips_whitespace(clean_env):
    clean_env.setenv("EP_TEST_STRIPPED", "  value  ")
    assert get_env("EP_TEST_STRIPPED") == "value"


def test_get_env_required_raises_when_missing(clean_env):
    with pytest.raises(MissingEnvironmentVariableError):
        get_env("EP_TEST_REQUIRED", required=True)


def test_get_env_required_returns_value_when_set(clean_env):
    clean_env.setenv("EP_TEST_REQUIRED", "ok")
    assert get_env("EP_TEST_REQUIRED", required=True) == "ok"


def test_get_int_returns_value(clean_env):
    clean_env.setenv("EP_TEST_INT", "42")
    assert get_int("EP_TEST_INT") == 42


def test_get_int_uses_default_when_missing(clean_env):
    assert get_int("EP_TEST_INT", default=7) == 7


def test_get_int_uses_default_when_garbage(clean_env):
    clean_env.setenv("EP_TEST_INT", "not-a-number")
    assert get_int("EP_TEST_INT", default=9) == 9


def test_get_int_negative(clean_env):
    clean_env.setenv("EP_TEST_INT", "-5")
    assert get_int("EP_TEST_INT") == -5


@pytest.mark.parametrize("value,expected", [
    ("1", True), ("true", True), ("TRUE", True), ("yes", True), ("on", True),
    ("y", True), ("t", True),
    ("0", False), ("false", False), ("NO", False), ("off", False),
    ("n", False), ("f", False),
])
def test_get_bool_recognizes_common_values(clean_env, value, expected):
    clean_env.setenv("EP_TEST_BOOL", value)
    assert get_bool("EP_TEST_BOOL") is expected


def test_get_bool_uses_default_when_missing(clean_env):
    assert get_bool("EP_TEST_BOOL", default=True) is True


def test_get_bool_unknown_value_uses_default(clean_env):
    clean_env.setenv("EP_TEST_BOOL", "maybe")
    assert get_bool("EP_TEST_BOOL", default=False) is False


def test_get_list_splits(clean_env):
    clean_env.setenv("EP_TEST_LIST", "a, b ,c")
    assert get_list("EP_TEST_LIST") == ["a", "b", "c"]


def test_get_list_drops_empty_items(clean_env):
    clean_env.setenv("EP_TEST_LIST", "a,,b,")
    assert get_list("EP_TEST_LIST") == ["a", "b"]


def test_get_list_uses_default_when_missing(clean_env):
    assert get_list("EP_TEST_LIST", default=["x"]) == ["x"]


def test_get_list_custom_separator(clean_env):
    clean_env.setenv("EP_TEST_LIST", "a;b;c")
    assert get_list("EP_TEST_LIST", sep=";") == ["a", "b", "c"]
