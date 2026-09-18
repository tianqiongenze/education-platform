"""Reflexion reflection loop.

The evaluator uses an **LLM-as-judge** (via the AI gateway OpenAI-compatible
endpoint) to score answer quality. When no LLM endpoint is configured the
evaluator falls back to a minimal length/placeholder sanity check (test mode).

Depth control is delegated to LangGraph's ``recursion_limit`` (set at compile
time in each subgraph). The ``should_retry`` conditional edge only decides
*whether* another reflection pass is warranted based on the judge verdict; it
does not implement its own retry counter — LangGraph terminates the loop when
``recursion_limit`` is reached.

Episodic memory (past reflections) is persisted in Redis with a 30-day TTL
and re-injected on subsequent runs.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from .config import config
from .state import AgentState

logger = logging.getLogger("ai_agent.reflexion")

# Minimal fallback markers used only when no LLM judge is available.
_PLACEHOLDER_MARKERS = (
    "i am not sure",
    "as an ai",
    "mock reasoning answer",
    "todo",
    "placeholder",
)


def _last_user_message(state: AgentState) -> str:
    for m in reversed(state.get("messages", [])):
        if m.get("role") == "user":
            return m.get("content", "")
    return ""


# --------------------------------------------------------------------------- #
# LLM-as-judge evaluator
# --------------------------------------------------------------------------- #

_JUDGE_SYSTEM_PROMPT = (
    "You are a strict answer-quality evaluator for an education platform. "
    "Score the candidate answer against the user's question on a 0.0-1.0 scale. "
    "0.0 = empty/placeholder/hallucinated; 1.0 = accurate, specific, grounded. "
    'Respond as JSON: {"score": <float>, "feedback": "<short reason>", "pass": <true|false>}.'
)


def _call_llm_judge(question: str, answer: str) -> tuple[bool, str, float] | None:
    """Call the AI gateway LLM to judge answer quality.

    Returns ``(passed, feedback, score)`` or ``None`` when the LLM is
    unavailable (no gateway URL configured or call failed). Callers fall
    back to the heuristic evaluator in that case.
    """
    gateway_url = getattr(config, "llm_api_url", None) or None
    if not gateway_url:
        return None
    try:
        import httpx  # type: ignore

        user_msg = f"Question: {question}\nAnswer: {answer}"
        api_key = getattr(config, "llm_api_key", None) or "sk-unused"
        model = getattr(config, "agent_model", "gpt-4o")
        with httpx.Client(timeout=getattr(config, "llm_timeout_sec", 30)) as client:
            resp = client.post(
                f"{gateway_url.rstrip('/')}/v1/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json={
                    "model": model,
                    "temperature": 0.0,
                    "messages": [
                        {"role": "system", "content": _JUDGE_SYSTEM_PROMPT},
                        {"role": "user", "content": user_msg},
                    ],
                },
            )
            resp.raise_for_status()
            content = resp.json()["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        score = float(parsed.get("score", 0.0))
        passed = bool(parsed.get("pass", score >= config.reflexion_threshold))
        feedback = str(parsed.get("feedback", ""))
        return passed, feedback, score
    except Exception as exc:  # noqa: BLE001
        logger.warning("reflexion.llm_judge.unavailable reason=%s", exc)
        return None


def _heuristic_fallback(answer: str, question: str) -> tuple[bool, str, float]:
    """Minimal sanity check used only when the LLM judge is unavailable.

    This is NOT the primary evaluator — it exists so tests / dev mode
    without a gateway still produce a usable verdict.
    """
    if not answer or not answer.strip():
        return False, "Answer is empty.", 0.0
    if len(answer.strip()) < 10:
        return False, "Answer is too short to be useful.", 0.2
    lowered = answer.lower()
    for marker in _PLACEHOLDER_MARKERS:
        if marker in lowered:
            return False, f"Answer contains hedge/placeholder marker: '{marker}'.", 0.3
    if question:
        q_words = {w for w in question.lower().split() if len(w) > 3}
        a_words = set(lowered.split())
        if q_words and not (q_words & a_words):
            return False, "Answer does not address the question keywords.", 0.4
    return True, "Answer passes sanity checks.", 0.8


def _evaluate(answer: str, question: str) -> tuple[bool, str, float]:
    """Run the LLM-as-judge evaluator, falling back to heuristics."""
    judged = _call_llm_judge(question, answer)
    if judged is not None:
        return judged
    return _heuristic_fallback(answer, question)


def evaluate_node(state: AgentState) -> dict:
    """Evaluate current answer quality via LLM-as-judge.

    Returns: {evaluation_passed, feedback, score, trial_count: +1}

    The ``trial_count`` is incremented for observability; depth control is
    enforced by LangGraph's ``recursion_limit`` (set at compile time).
    """
    answer = state.get("final_answer") or ""
    question = _last_user_message(state)
    passed, feedback, score = _evaluate(answer, question)
    if not passed and score < config.reflexion_threshold:
        passed = False
    trial_count = state.get("trial_count", 0) + 1
    return {
        "evaluation_passed": passed,
        "feedback": feedback,
        "score": score,
        "trial_count": trial_count,
    }


def reflect_node(state: AgentState) -> dict:
    """Generate an actionable reflection based on feedback + current answer.

    Appends to state.reflections and resets current_node. When the AI
    gateway LLM is available this node can be upgraded to call it for richer
    reflections; the deterministic template is the safe default.
    """
    feedback = state.get("feedback", "unknown issue")
    answer = state.get("final_answer") or ""
    trial = state.get("trial_count", 0)
    reflection = (
        f"Trial {trial}: previous answer '{answer[:60]}' was rejected ({feedback}). "
        "Next attempt must be more specific, grounded in retrieved context, and avoid placeholders."
    )
    reflections = list(state.get("reflections", []))
    reflections.append(reflection)
    return {"reflections": reflections, "current_node": "reflect"}


def should_retry(state: AgentState) -> str:
    """Conditional edge after evaluate.

    - pass -> "respond"
    - fail -> "reflect"  (LangGraph's recursion_limit caps the loop)

    Depth control is delegated to LangGraph's ``recursion_limit``; this
    function no longer implements its own trial counter gate.
    """
    if state.get("evaluation_passed"):
        return "respond"
    return "reflect"


# --- Episodic memory (Redis-backed) -----------------------------------------


def _episodic_key(tenant_id: str | None, agent_name: str, user_id: str | None) -> str:
    return f"mem:episodic:{tenant_id or 'default'}:{agent_name}:{user_id or 'default'}"


def _get_redis():
    """Return a Redis client if configured and importable, else None."""
    url = config.redis_url
    if not url:
        return None
    try:
        import redis  # type: ignore

        return redis.from_url(url, decode_responses=True)
    except Exception:
        return None


def load_episodic_memory(
    tenant_id: str | None, agent_name: str, user_id: str | None
) -> list[str]:
    """Load past reflections from Redis. Returns [] if unavailable."""
    client = _get_redis()
    if client is None:
        return []
    try:
        raw = client.get(_episodic_key(tenant_id, agent_name, user_id))
        if not raw:
            return []
        data: Any = json.loads(raw)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def save_episodic_memory(
    tenant_id: str | None,
    agent_name: str,
    user_id: str | None,
    reflection: str,
) -> None:
    """Persist a reflection to Redis with a 30-day TTL (2592000s)."""
    client = _get_redis()
    if client is None or not reflection:
        return
    key = _episodic_key(tenant_id, agent_name, user_id)
    try:
        existing = load_episodic_memory(tenant_id, agent_name, user_id)
        existing.append(reflection)
        # cap to last 50 reflections
        existing = existing[-50:]
        client.set(key, json.dumps(existing), ex=2592000)
    except Exception:
        # best-effort persistence
        pass
