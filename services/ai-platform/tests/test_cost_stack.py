"""D-178 — AI gateway cost stack tests.

Each layer is tested independently, plus an integration test of the full
CostStack. The SemanticCache tests use a lightweight fake Redis (no real
cluster required) so the suite runs anywhere. The cascade / router / fallback
layers are pure-Python and need no mocking.
"""

from __future__ import annotations

from typing import Any

import pytest

from ai_gateway.cost.confidence_cascade import ConfidenceCascade
from ai_gateway.cost.cost_stack import CostStack
from ai_gateway.cost.provider_fallback import ProviderFallback
from ai_gateway.cost.route_router import FLAGSHIP_MODEL, SMALL_MODEL, RouteRouter
from ai_gateway.cost.semantic_cache import (
    SemanticCache,
    _CACHE_NAME,
    _CACHE_PREFIX,
    _CACHE_TTL_SEC,
)
from ai_gateway.models import ChatMessage, ChatRequest, ChatResponse


# --------------------------------------------------------------------------- #
# Test doubles
# --------------------------------------------------------------------------- #


class FakeRedis:
    """In-memory redis.Redis stand-in implementing the subset we use."""

    def __init__(self) -> None:
        self.store: dict[str, str] = {}
        self.calls: list[tuple[str, tuple, dict]] = []
        self.fail: bool = False
        # Pub/Sub state: channels with active subscribers and the messages
        # published to them. CacheService's background thread subscribes to
        # ``ep:cache:invalidate``; we record subscribers so publish() can
        # deliver messages synchronously to mimic real Redis pubsub semantics.
        self._subscribers: dict[str, list] = {}

    def ping(self) -> bool:
        if self.fail:
            raise RuntimeError("fake redis: ping failed")
        return True

    def get(self, key: str) -> str | None:
        self.calls.append(("get", (key,), {}))
        if self.fail:
            raise RuntimeError("fake redis: get failed")
        return self.store.get(key)

    def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.calls.append(("set", (key, value), {"ex": ex}))
        if self.fail:
            raise RuntimeError("fake redis: set failed")
        self.store[key] = value

    def delete(self, key: str) -> int:
        self.calls.append(("delete", (key,), {}))
        return 1 if self.store.pop(key, None) is not None else 0

    # -- Pub/Sub (used by CacheService's invalidation broadcast) --

    def pubsub(self, ignore_subscribe_messages: bool = False):
        return _FakePubSub(self)

    def pubsub_channels(self, pattern: str | None = None):
        return [c.encode() for c in self._subscribers if self._subscribers[c]]

    def publish(self, channel: str, message: str) -> int:
        delivered = 0
        for sub in self._subscribers.get(channel, []):
            sub._deliver(channel, message)  # noqa: SLF001
            delivered += 1
        return delivered


class _FakePubSub:
    """Minimal pubsub stand-in for CacheService's invalidation listener."""

    def __init__(self, fake: "FakeRedis") -> None:
        self._fake = fake
        self._channel: str | None = None
        self._queue: list[dict] = []
        self._closed = False

    def subscribe(self, channel: str) -> None:
        self._channel = channel
        self._fake._subscribers.setdefault(channel, []).append(self)  # noqa: SLF001

    def listen(self):
        # Generator that yields queued messages as they arrive. Blocks
        # (polls) until the fake is closed — CacheService runs this in a
        # daemon thread so it never blocks process exit.
        import time as _time

        while not self._closed:
            if self._queue:
                yield self._queue.pop(0)
            else:
                _time.sleep(0.01)

    def close(self) -> None:
        self._closed = True
        if self._channel is not None:
            subs = self._fake._subscribers.get(self._channel, [])  # noqa: SLF001
            if self in subs:
                subs.remove(self)

    def _deliver(self, channel: str, message: str) -> None:
        self._queue.append({"type": "message", "channel": channel, "data": message})


def _chat_response(
    content: str = "hello world",
    model: str = "gpt-4o",
    resp_id: str = "resp-1",
) -> ChatResponse:
    return ChatResponse(
        id=resp_id,
        model=model,
        choices=[
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        usage={"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
    )


class _ScriptedProvider:
    """Provider that returns scripted responses / raises scripted errors.

    Each call pops the next behavior from ``behaviors``.
    """

    def __init__(self, name: str, behaviors: list[Any]) -> None:
        self.name = name
        self._behaviors = list(behaviors)
        self.call_count = 0

    def chat(self, request: ChatRequest) -> ChatResponse:
        self.call_count += 1
        behavior = self._behaviors.pop(0) if self._behaviors else _chat_response()
        if isinstance(behavior, Exception):
            raise behavior
        if callable(behavior):
            return behavior(request)
        return behavior


# --------------------------------------------------------------------------- #
# Layer 1 — SemanticCache
# --------------------------------------------------------------------------- #


def test_semantic_cache_hit_miss():
    fake = FakeRedis()
    cache = SemanticCache(client=fake)

    msgs = [ChatMessage(role="user", content="What is 2+2?")]

    # Miss before put.
    assert cache.get(msgs) is None

    # Put, then hit.
    resp = _chat_response(content="4")
    cache.put(msgs, resp)
    got = cache.get(msgs)
    assert got is not None
    assert got.id == resp.id
    assert got.model == resp.model
    assert got.choices[0]["message"]["content"] == "4"

    # Different prompt → miss.
    other = [ChatMessage(role="user", content="What is 3+3?")]
    assert cache.get(other) is None


def test_semantic_cache_static_hash_is_deterministic():
    a = [ChatMessage(role="user", content="hello")]
    b = [ChatMessage(role="user", content="hello")]
    assert SemanticCache.hash(a) == SemanticCache.hash(b)
    # Different content → different hash.
    c = [ChatMessage(role="user", content="world")]
    assert SemanticCache.hash(a) != SemanticCache.hash(c)


def test_semantic_cache_redis_key_and_ttl():
    fake = FakeRedis()
    cache = SemanticCache(client=fake)
    msgs = [ChatMessage(role="user", content="cache me")]
    cache.put(msgs, _chat_response())
    # The set call captured the key + TTL.
    set_calls = [c for c in fake.calls if c[0] == "set"]
    assert len(set_calls) == 1
    _name, args, kwargs = set_calls[0]
    key, value = args
    assert key.startswith(_CACHE_PREFIX)
    assert key == _CACHE_PREFIX + SemanticCache.hash(msgs)
    assert kwargs["ex"] == _CACHE_TTL_SEC


def test_semantic_cache_degrades_without_redis():
    # Passing client=None triggers _build_redis_client(), which will try the
    # real env. Force-override to L1-only to simulate total unavailability.
    cache = SemanticCache(client=None)
    cache._cache._redis = None  # noqa: SLF001 — simulate no-Redis in tests
    msgs = [ChatMessage(role="user", content="anything")]
    assert cache.get(msgs) is None
    # put is a no-op on L2 (does not raise); L1 stores it but get still
    # returns None because the loader is a no-op lambda.
    cache.put(msgs, _chat_response())
    assert not cache.available


def test_semantic_cache_handles_redis_errors_gracefully():
    fake = FakeRedis()
    fake.fail = True  # every op raises
    cache = SemanticCache(client=fake)
    msgs = [ChatMessage(role="user", content="boom")]
    assert cache.get(msgs) is None
    # put must not raise.
    cache.put(msgs, _chat_response())


def test_semantic_cache_corrupt_entry_evicted():
    fake = FakeRedis()
    cache = SemanticCache(client=fake)
    msgs = [ChatMessage(role="user", content="corrupt")]
    # Plant a corrupt JSON entry directly in the fake store. The full key
    # is "{cache_name}:{hash}" (CacheService builds it as cache_name:key).
    full_key = _CACHE_NAME + ":" + SemanticCache.hash(msgs)
    fake.store[full_key] = "not-json{"
    assert cache.get(msgs) is None
    # Eviction attempted → key removed.
    assert full_key not in fake.store


# --------------------------------------------------------------------------- #
# Layer 2 — RouteRouter
# --------------------------------------------------------------------------- #


def test_route_router_classify_simple():
    router = RouteRouter()
    msgs = [ChatMessage(role="user", content="hi")]
    assert router.classify(msgs) == "simple"
    assert router.route(msgs) == SMALL_MODEL


def test_route_router_classify_complex_long_prompt():
    router = RouteRouter()
    long = "x" * 150
    msgs = [ChatMessage(role="user", content=long)]
    assert router.classify(msgs) == "complex"
    assert router.route(msgs) == FLAGSHIP_MODEL


def test_route_router_classify_complex_code_block():
    router = RouteRouter()
    msgs = [ChatMessage(role="user", content="```python\nprint(1)\n```")]
    # Short, but contains a code fence → complex.
    assert router.classify(msgs) == "complex"


@pytest.mark.parametrize("kw", ["explain", "analyze", "design", "architect", "implement", "refactor"])
def test_route_router_classify_complex_keywords(kw):
    router = RouteRouter()
    msgs = [ChatMessage(role="user", content=f"Please {kw} this for me now please")]
    assert router.classify(msgs) == "complex"


def test_route_router_empty_prompt_is_complex():
    router = RouteRouter()
    msgs = [ChatMessage(role="system", content="be helpful")]
    assert router.classify(msgs) == "complex"


# --------------------------------------------------------------------------- #
# Layer 3 — ConfidenceCascade
# --------------------------------------------------------------------------- #


def test_cascade_should_escalate_short():
    cascade = ConfidenceCascade()
    resp = _chat_response(content="no")  # < 50 chars
    assert cascade.should_escalate(resp) is True
    info = cascade.evaluate(resp)
    assert info["confidence"] == "low"
    assert any("too_short" in r for r in info["reasons"])


def test_cascade_should_escalate_apology():
    cascade = ConfidenceCascade()
    resp = _chat_response(content="I'm not sure about the answer to that question right now, sorry.")
    assert cascade.should_escalate(resp) is True


def test_cascade_should_escalate_question_mark():
    cascade = ConfidenceCascade()
    resp = _chat_response(content="Did you mean to ask about something else entirely here?")
    assert cascade.should_escalate(resp) is True


def test_cascade_high_confidence():
    cascade = ConfidenceCascade()
    content = (
        "The answer is forty-two. Here is a thorough explanation of how we "
        "arrive at that result step by step with full reasoning included."
    )
    assert len(content) >= 50
    resp = _chat_response(content=content)
    assert cascade.should_escalate(resp) is False
    info = cascade.evaluate(resp)
    assert info["confidence"] == "high"
    assert info["reasons"] == []


def test_cascade_empty_choices_is_low():
    cascade = ConfidenceCascade()
    resp = ChatResponse(id="x", model="gpt-4o", choices=[], usage={})
    assert cascade.should_escalate(resp) is True


# --------------------------------------------------------------------------- #
# Layer 4 — ProviderFallback
# --------------------------------------------------------------------------- #


def test_provider_fallback_success():
    fb = ProviderFallback()
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])
    ok = _chat_response(content="hi back", resp_id="ok")
    providers = [_ScriptedProvider("primary", [ok])]
    resp, name = fb.call(req, providers)
    assert resp.id == "ok"
    assert name == "primary"
    assert providers[0].call_count == 1


def test_provider_fallback_fallback():
    fb = ProviderFallback()
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])
    ok = _chat_response(content="recovered", resp_id="ok2")
    providers = [
        _ScriptedProvider("primary", [ValueError("boom")]),
        _ScriptedProvider("secondary", [ok]),
    ]
    resp, name = fb.call(req, providers)
    assert resp.id == "ok2"
    assert name == "secondary"
    assert providers[0].call_count == 1
    assert providers[1].call_count == 1


def test_provider_fallback_all_fail_raises_last():
    fb = ProviderFallback()
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])
    providers = [
        _ScriptedProvider("primary", [ValueError("first")]),
        _ScriptedProvider("secondary", [RuntimeError("second")]),
    ]
    with pytest.raises(RuntimeError, match="second"):
        fb.call(req, providers)


def test_provider_fallback_empty_providers():
    fb = ProviderFallback()
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])
    with pytest.raises(ValueError, match="empty"):
        fb.call(req, providers=[])


def test_provider_fallback_accepts_bare_callables():
    fb = ProviderFallback()
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])

    def bare(_req: ChatRequest) -> ChatResponse:
        return _chat_response(content="bare", resp_id="bare")

    resp, name = fb.call(req, [bare])
    assert resp.id == "bare"
    # Bare callable → name derived from its class (function/lambda). It is
    # non-empty and identifies the provider as a callable.
    assert isinstance(name, str) and name


# --------------------------------------------------------------------------- #
# Full CostStack integration
# --------------------------------------------------------------------------- #


def _make_stack(fake_redis: FakeRedis | None = None) -> CostStack:
    cache = SemanticCache(client=fake_redis) if fake_redis else SemanticCache(client=None)
    if fake_redis is None:
        cache._cache._redis = None  # noqa: SLF001 — no-Redis path
    return CostStack(
        cache=cache,
        router=RouteRouter(),
        cascade=ConfidenceCascade(),
        fallback=ProviderFallback(),
    )


def test_cost_stack_cache_hit():
    fake = FakeRedis()
    stack = _make_stack(fake)
    msgs = [ChatMessage(role="user", content="ping")]
    req = ChatRequest(messages=msgs)

    # First call: miss → produces & caches a response.
    good = _chat_response(content="pong " * 20, resp_id="r1")  # >50 chars, high conf
    providers = [_ScriptedProvider("p", [good])]
    resp1, meta1 = stack.execute(req, providers, [_ScriptedProvider("f", [good])])
    assert meta1["layer"] == "router"
    assert resp1.id == "r1"

    # Second identical call: cache hit.
    req2 = ChatRequest(messages=msgs)
    resp2, meta2 = stack.execute(
        req2,
        [_ScriptedProvider("p-should-not-run", [_chat_response(resp_id="nope")])],
        [_ScriptedProvider("f-should-not-run", [_chat_response(resp_id="nope")])],
    )
    assert meta2["layer"] == "cache"
    assert meta2["cached"] is True
    assert resp2.id == "r1"


def test_cost_stack_simple_no_escalate():
    fake = FakeRedis()
    stack = _make_stack(fake)
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])  # simple
    good = _chat_response(content="Hello there, I can help you with that question today.", resp_id="ok")
    providers = [_ScriptedProvider("small", [good])]
    resp, meta = stack.execute(req, providers, [_ScriptedProvider("flag", [good])])
    assert meta["layer"] == "router"
    assert meta["classified"] == "simple"
    assert meta["model"] == SMALL_MODEL
    assert resp.id == "ok"
    assert providers[0].call_count == 1


def test_cost_stack_simple_escalate():
    fake = FakeRedis()
    stack = _make_stack(fake)
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])  # simple
    bad = _chat_response(content="huh?", resp_id="bad")  # <50 chars → low confidence
    flagship = _chat_response(content="Here is a full, confident answer to your question.", resp_id="flag")
    providers = [_ScriptedProvider("small", [bad])]
    flagship_providers = [_ScriptedProvider("flag", [flagship])]
    resp, meta = stack.execute(req, providers, flagship_providers)
    assert meta["layer"] == "cascade"
    assert meta["escalated"] is True
    assert meta["original_model"] == SMALL_MODEL
    assert resp.id == "flag"
    # The flagship response is what we get back.
    assert resp.choices[0]["message"]["content"].startswith("Here is a full")
    assert providers[0].call_count == 1
    assert flagship_providers[0].call_count == 1


def test_cost_stack_complex():
    fake = FakeRedis()
    stack = _make_stack(fake)
    # Long prompt → complex → straight to flagship, cascade skipped.
    req = ChatRequest(messages=[ChatMessage(role="user", content="explain " + "x" * 200)])
    flagship = _chat_response(content="Detailed explanation goes here.", resp_id="flag")
    # Small providers should NOT be called for complex prompts.
    small = _ScriptedProvider("small", [_chat_response(resp_id="should-not-run")])
    flag = _ScriptedProvider("flag", [flagship])
    resp, meta = stack.execute(req, [small], [flag])
    assert meta["layer"] == "router"
    assert meta["classified"] == "complex"
    assert meta["model"] == FLAGSHIP_MODEL
    assert resp.id == "flag"
    assert small.call_count == 0
    assert flag.call_count == 1


def test_cost_stack_stores_final_response_in_cache():
    fake = FakeRedis()
    stack = _make_stack(fake)
    req = ChatRequest(messages=[ChatMessage(role="user", content="hi")])
    good = _chat_response(content="Hello there, I can help you with that question today.", resp_id="ok")
    stack.execute(req, [_ScriptedProvider("small", [good])], [_ScriptedProvider("flag", [good])])
    # Cache should now contain the response under the prompt hash. The full
    # key is "{cache_name}:{hash}" (CacheService builds it as cache_name:key).
    key = _CACHE_NAME + ":" + SemanticCache.hash(req.messages)
    assert key in fake.store
