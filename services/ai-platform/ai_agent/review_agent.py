"""D-191: AI Review Agent for boardgame post-game analysis.

This module implements a LangGraph-based agent that reviews a completed game
(Xiangqi PGN or Go SGF) and produces move-by-move annotations via the AI
AI Gateway OpenAI-compatible endpoint.

Flow:
    ingest -> analyze_moves -> evaluate -> [reflect -> analyze_moves]? -> respond

The ``analyze_moves`` node calls the LLM gateway (``/v1/chat/completions``)
with the full move list and asks for structured per-move commentary. When no
gateway URL is configured the agent falls back to a deterministic heuristic
annotation (dev-only mode), so tests and local development work without an LLM.
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx
from langgraph.graph import END, START, StateGraph
from typing_extensions import TypedDict

logger = logging.getLogger("ai_agent.review_agent")

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #


@dataclass
class ReviewConfig:
    """Runtime configuration for the review agent."""

    gateway_url: str | None = None
    api_key: str | None = None
    model: str = "gpt-4o"
    timeout_sec: int = 30
    max_reflections: int = 2

    @staticmethod
    def load() -> "ReviewConfig":
        import os

        return ReviewConfig(
            gateway_url=os.environ.get("AI_GATEWAY_URL") or os.environ.get("LLM_API_URL"),
            api_key=os.environ.get("AI_GATEWAY_API_KEY") or os.environ.get("LLM_API_KEY"),
            model=os.environ.get("REVIEW_AGENT_MODEL", "gpt-4o"),
            timeout_sec=int(os.environ.get("REVIEW_AGENT_TIMEOUT", "30")),
            max_reflections=int(os.environ.get("REVIEW_AGENT_MAX_REFLECTIONS", "2")),
        )


# --------------------------------------------------------------------------- #
# Data models
# --------------------------------------------------------------------------- #


@dataclass
class MoveAnnotation:
    """A single move's AI annotation."""

    move_number: int
    move: str
    evaluation: str  # "good", "mistake", "blunder", "excellent", "inaccuracy"
    comment: str
    best_move: str | None = None


@dataclass
class ReviewResult:
    """Aggregated review output."""

    session_id: str
    game_type: str
    result: str
    summary: str
    annotations: list[MoveAnnotation] = field(default_factory=list)
    overall_quality: float = 0.0


class ReviewState(TypedDict, total=False):
    """LangGraph state for the review agent."""

    session_id: str
    game_type: str  # "XIANGQI" or "GO"
    moves: str  # PGN (Xiangqi) or SGF (Go)
    result: str
    annotations: list[dict]
    summary: str
    overall_quality: float
    reflections: list[str]
    trial_count: int
    evaluation_passed: bool
    final_answer: str | None
    error: str | None


# --------------------------------------------------------------------------- #
# LLM gateway client
# --------------------------------------------------------------------------- #

_SYSTEM_PROMPT = (
    "You are an expert boardgame analyst for an education platform. "
    "Given a game's move list and result, provide a move-by-move analysis. "
    "For each move, classify it as one of: excellent, good, inaccuracy, mistake, blunder. "
    "Respond as JSON: {\"summary\": \"<overall summary>\", "
    "\"quality\": <0.0-1.0>, "
    "\"annotations\": [{\"move_number\": <int>, \"move\": \"<notation>\", "
    "\"evaluation\": \"<classification>\", \"comment\": \"<explanation>\", "
    "\"best_move\": \"<suggested better move or null>\"}]}"
)


def _call_llm(
    config: ReviewConfig,
    game_type: str,
    moves: str,
    result: str,
    reflections: list[str] | None = None,
) -> dict[str, Any] | None:
    """Call the AI Gateway chat-completions endpoint for game analysis.

    Returns the parsed JSON response or ``None`` when the gateway is
    unavailable (no URL configured or request failed).
    """
    if not config.gateway_url:
        return None
    try:
        user_content = (
            f"Game type: {game_type}\nResult: {result}\nMoves:\n{moves}"
        )
        if reflections:
            user_content += "\n\nPrevious reflections:\n" + "\n".join(reflections)
        with httpx.Client(timeout=config.timeout_sec) as client:
            resp = client.post(
                f"{config.gateway_url.rstrip('/')}/v1/chat/completions",
                headers={"Authorization": f"Bearer {config.api_key or 'sk-unused'}"},
                json={
                    "model": config.model,
                    "temperature": 0.2,
                    "messages": [
                        {"role": "system", "content": _SYSTEM_PROMPT},
                        {"role": "user", "content": user_content},
                    ],
                },
            )
            resp.raise_for_status()
            content = resp.json()["choices"][0]["message"]["content"]
        return json.loads(content)
    except Exception as exc:  # noqa: BLE001
        logger.warning("review_agent.llm.unavailable reason=%s", exc)
        return None


def _heuristic_analysis(game_type: str, moves: str, result: str) -> dict[str, Any]:
    """Deterministic fallback when no LLM gateway is available.

    Produces a minimal annotation set so the agent remains functional in
    dev/test mode. This is NOT a substitute for real LLM analysis.
    """
    move_list = [m for m in moves.replace(";", " ").split() if m.strip()]
    annotations = []
    for i, mv in enumerate(move_list):
        # Simple heuristic: classify early moves as "good", later ones heuristically.
        if i < 10:
            evaluation = "good"
            comment = f"Opening phase — move {mv} follows standard principles."
        elif "x" in mv.lower():
            evaluation = "good"
            comment = f"Capture {mv} gains material."
        else:
            evaluation = "inaccuracy"
            comment = f"Move {mv} may warrant review."
        annotations.append({
            "move_number": i + 1,
            "move": mv,
            "evaluation": evaluation,
            "comment": comment,
            "best_move": None,
        })
    return {
        "summary": f"Heuristic analysis of {len(move_list)} moves. Result: {result}.",
        "quality": 0.6,
        "annotations": annotations,
    }


# --------------------------------------------------------------------------- #
# LangGraph nodes
# --------------------------------------------------------------------------- #


def ingest_node(state: ReviewState) -> dict:
    """Validate and normalize the input game data."""
    if not state.get("moves"):
        return {"error": "No moves provided for review"}
    if state.get("game_type") not in ("XIANGQI", "GO"):
        return {"error": f"Unsupported game type: {state.get('game_type')}"}
    session_id = state.get("session_id") or str(uuid.uuid4())
    return {"session_id": session_id, "trial_count": 0, "reflections": []}


def analyze_moves_node(config: ReviewConfig):
    """Factory: returns the analyze_moves node bound to ``config``."""

    def _node(state: ReviewState) -> dict:
        moves = state["moves"]
        game_type = state["game_type"]
        result = state.get("result", "UNKNOWN")
        reflections = state.get("reflections", [])

        llm_result = _call_llm(config, game_type, moves, result, reflections)
        if llm_result is None:
            llm_result = _heuristic_analysis(game_type, moves, result)

        annotations = llm_result.get("annotations", [])
        summary = llm_result.get("summary", "")
        quality = float(llm_result.get("quality", 0.0))
        return {
            "annotations": annotations,
            "summary": summary,
            "overall_quality": quality,
        }

    return _node


def evaluate_node(state: ReviewState) -> dict:
    """Check whether the analysis is sufficient.

    The evaluator passes when annotations exist and the quality score is
    above a threshold (0.5), or when we've exhausted reflection attempts.
    """
    annotations = state.get("annotations", [])
    quality = state.get("overall_quality", 0.0)
    trial = state.get("trial_count", 0)
    passed = len(annotations) > 0 and (quality >= 0.5 or trial >= 2)
    return {"evaluation_passed": passed, "trial_count": trial + 1}


def reflect_node(state: ReviewState) -> dict:
    """Generate a reflection for the next analysis pass."""
    reflections = list(state.get("reflections", []))
    quality = state.get("overall_quality", 0.0)
    reflections.append(
        f"Previous analysis quality was {quality:.2f}; "
        "focus on moves with unclear evaluations."
    )
    return {"reflections": reflections}


def respond_node(state: ReviewState) -> dict:
    """Produce the final ReviewResult."""
    annotations = [
        MoveAnnotation(
            move_number=a.get("move_number", 0),
            move=a.get("move", ""),
            evaluation=a.get("evaluation", "good"),
            comment=a.get("comment", ""),
            best_move=a.get("best_move"),
        )
        for a in state.get("annotations", [])
    ]
    result = ReviewResult(
        session_id=state.get("session_id", ""),
        game_type=state.get("game_type", ""),
        result=state.get("result", ""),
        summary=state.get("summary", ""),
        annotations=annotations,
        overall_quality=state.get("overall_quality", 0.0),
    )
    return {"final_answer": result}


def _should_retry(state: ReviewState) -> str:
    if state.get("error"):
        return "respond"
    if state.get("evaluation_passed"):
        return "respond"
    return "reflect"


# --------------------------------------------------------------------------- #
# Graph builder
# --------------------------------------------------------------------------- #


def build_review_graph(config: ReviewConfig | None = None):
    """Compile and return the review agent LangGraph.

    ``config`` defaults to :meth:`ReviewConfig.load`.
    """
    cfg = config or ReviewConfig.load()
    graph = StateGraph(ReviewState)
    graph.add_node("ingest", ingest_node)
    graph.add_node("analyze_moves", analyze_moves_node(cfg))
    graph.add_node("evaluate", evaluate_node)
    graph.add_node("reflect", reflect_node)
    graph.add_node("respond", respond_node)

    graph.add_edge(START, "ingest")
    graph.add_edge("ingest", "analyze_moves")
    graph.add_edge("analyze_moves", "evaluate")
    graph.add_conditional_edges(
        "evaluate",
        _should_retry,
        {"respond": "respond", "reflect": "reflect"},
    )
    graph.add_edge("reflect", "analyze_moves")
    graph.add_edge("respond", END)
    return graph.compile().with_config({"recursion_limit": cfg.max_reflections * 4 + 10})


# --------------------------------------------------------------------------- #
# High-level API
# --------------------------------------------------------------------------- #


class ReviewSession:
    """High-level facade for running a game review.

    Example::

        session = ReviewSession()
        result = session.analyze_game(
            game_type="GO",
            moves=";B[dd];W[pp];B[ed]",
            result="BLACK_WIN",
        )
    """

    def __init__(self, config: ReviewConfig | None = None) -> None:
        self.config = config or ReviewConfig.load()
        self.graph = build_review_graph(self.config)

    def analyze_game(
        self,
        game_type: str,
        moves: str,
        result: str = "UNKNOWN",
        session_id: str | None = None,
    ) -> ReviewResult:
        """Run the full review pipeline and return the result."""
        initial: ReviewState = {
            "game_type": game_type,
            "moves": moves,
            "result": result,
            "session_id": session_id,
        }
        final = self.graph.invoke(initial)
        if final.get("error"):
            raise ValueError(final["error"])
        answer = final.get("final_answer")
        if isinstance(answer, ReviewResult):
            return answer
        # Fallback: reconstruct from state if respond node didn't fire.
        return ReviewResult(
            session_id=final.get("session_id", ""),
            game_type=game_type,
            result=result,
            summary=final.get("summary", ""),
            annotations=[
                MoveAnnotation(
                    move_number=a.get("move_number", 0),
                    move=a.get("move", ""),
                    evaluation=a.get("evaluation", "good"),
                    comment=a.get("comment", ""),
                    best_move=a.get("best_move"),
                )
                for a in final.get("annotations", [])
            ],
            overall_quality=final.get("overall_quality", 0.0),
        )

    def get_review(self, result: ReviewResult) -> dict[str, Any]:
        """Serialize a ReviewResult to a dict for API responses."""
        return {
            "session_id": result.session_id,
            "game_type": result.game_type,
            "result": result.result,
            "summary": result.summary,
            "overall_quality": result.overall_quality,
            "annotations": [
                {
                    "move_number": a.move_number,
                    "move": a.move,
                    "evaluation": a.evaluation,
                    "comment": a.comment,
                    "best_move": a.best_move,
                }
                for a in result.annotations
            ],
        }
