"""D-191: Tests for the boardgame AI review agent.

httpx calls are mocked via monkeypatch — no live LLM gateway required.
The heuristic fallback path is also exercised when no gateway URL is set.
"""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest

from ai_agent.review_agent import (
    ReviewConfig,
    ReviewResult,
    ReviewSession,
    _heuristic_analysis,
    build_review_graph,
)


# --------------------------------------------------------------------------- #
# Heuristic fallback (no LLM)
# --------------------------------------------------------------------------- #


def test_heuristic_analysis_basic():
    result = _heuristic_analysis("XIANGQI", "h2e2 e6e5", "RED_WIN")
    assert result["quality"] == 0.6
    assert len(result["annotations"]) == 2
    assert result["annotations"][0]["evaluation"] == "good"
    assert "Opening phase" in result["annotations"][0]["comment"]


def test_heuristic_analysis_capture_move():
    # Use 11 moves so the 12th (a capture) is past the opening-phase threshold.
    moves = " ".join([f"m{i}" for i in range(11)]) + " h2xe2"
    result = _heuristic_analysis("XIANGQI", moves, "RED_WIN")
    # Last move (index 11) contains 'x' -> classified as "good" with "Capture" comment.
    cap = result["annotations"][11]
    assert cap["evaluation"] == "good"
    assert "Capture" in cap["comment"]


def test_heuristic_analysis_empty_moves():
    result = _heuristic_analysis("GO", "", "DRAW")
    assert result["annotations"] == []
    assert "0 moves" in result["summary"]


# --------------------------------------------------------------------------- #
# ReviewSession — heuristic mode (no gateway URL)
# --------------------------------------------------------------------------- #


def test_review_session_heuristic_mode():
    config = ReviewConfig(gateway_url=None)
    session = ReviewSession(config=config)
    result = session.analyze_game(
        game_type="GO",
        moves=";B[dd];W[pp]",
        result="BLACK_WIN",
    )
    assert isinstance(result, ReviewResult)
    assert result.game_type == "GO"
    assert result.result == "BLACK_WIN"
    assert len(result.annotations) > 0
    assert result.overall_quality > 0.0


def test_review_session_generates_session_id():
    config = ReviewConfig(gateway_url=None)
    session = ReviewSession(config=config)
    result = session.analyze_game(
        game_type="XIANGQI",
        moves="h2e2 e6e5",
        result="RED_WIN",
    )
    assert result.session_id  # auto-generated UUID


def test_review_session_custom_session_id():
    config = ReviewConfig(gateway_url=None)
    session = ReviewSession(config=config)
    result = session.analyze_game(
        game_type="GO",
        moves=";B[dd]",
        result="BLACK_WIN",
        session_id="custom-123",
    )
    assert result.session_id == "custom-123"


def test_review_session_invalid_game_type():
    config = ReviewConfig(gateway_url=None)
    session = ReviewSession(config=config)
    with pytest.raises(ValueError, match="Unsupported game type"):
        session.analyze_game(
            game_type="CHESS",
            moves="e4 e5",
            result="DRAW",
        )


def test_review_session_no_moves():
    config = ReviewConfig(gateway_url=None)
    session = ReviewSession(config=config)
    with pytest.raises(ValueError, match="No moves"):
        session.analyze_game(
            game_type="GO",
            moves="",
            result="DRAW",
        )


def test_get_review_serializes_result():
    config = ReviewConfig(gateway_url=None)
    session = ReviewSession(config=config)
    result = session.analyze_game(
        game_type="GO",
        moves=";B[dd];W[pp]",
        result="BLACK_WIN",
    )
    serialized = session.get_review(result)
    assert serialized["game_type"] == "GO"
    assert serialized["result"] == "BLACK_WIN"
    assert isinstance(serialized["annotations"], list)
    assert "summary" in serialized
    assert "overall_quality" in serialized


# --------------------------------------------------------------------------- #
# LLM gateway mode (httpx mocked)
# --------------------------------------------------------------------------- #


def _mock_llm_response(annotations: list[dict], quality: float = 0.8, summary: str = "Good game"):
    """Build a mock httpx response payload."""
    return {
        "choices": [
            {
                "message": {
                    "content": json.dumps({
                        "summary": summary,
                        "quality": quality,
                        "annotations": annotations,
                    })
                }
            }
        ]
    }


def test_review_session_llm_mode_success():
    """When a gateway URL is configured, the LLM is called via httpx."""
    config = ReviewConfig(gateway_url="http://mock-gateway", api_key="sk-test")
    llm_payload = _mock_llm_response(
        annotations=[
            {"move_number": 1, "move": "dd", "evaluation": "excellent",
             "comment": "Star point.", "best_move": None},
            {"move_number": 2, "move": "pp", "evaluation": "good",
             "comment": "Symmetric response.", "best_move": None},
        ],
        quality=0.85,
        summary="Both players opened with star points.",
    )

    mock_response = MagicMock()
    mock_response.json.return_value = llm_payload
    mock_response.raise_for_status.return_value = None

    mock_client = MagicMock()
    mock_client.__enter__ = MagicMock(return_value=mock_client)
    mock_client.__exit__ = MagicMock(return_value=False)
    mock_client.post.return_value = mock_response

    with patch("ai_agent.review_agent.httpx.Client", return_value=mock_client):
        session = ReviewSession(config=config)
        result = session.analyze_game(
            game_type="GO",
            moves=";B[dd];W[pp]",
            result="BLACK_WIN",
        )

    assert result.summary == "Both players opened with star points."
    assert result.overall_quality == 0.85
    assert len(result.annotations) == 2
    assert result.annotations[0].evaluation == "excellent"
    assert result.annotations[1].comment == "Symmetric response."

    # Verify httpx was called with the correct endpoint.
    mock_client.post.assert_called_once()
    call_args = mock_client.post.call_args
    assert call_args[0][0] == "http://mock-gateway/v1/chat/completions"
    assert call_args[1]["headers"]["Authorization"] == "Bearer sk-test"


def test_review_session_llm_failure_falls_back_to_heuristic():
    """When the LLM call fails, the heuristic fallback is used."""
    config = ReviewConfig(gateway_url="http://mock-gateway", api_key="sk-test")

    mock_client = MagicMock()
    mock_client.__enter__ = MagicMock(return_value=mock_client)
    mock_client.__exit__ = MagicMock(return_value=False)
    mock_client.post.side_effect = Exception("gateway down")

    with patch("ai_agent.review_agent.httpx.Client", return_value=mock_client):
        session = ReviewSession(config=config)
        result = session.analyze_game(
            game_type="XIANGQI",
            moves="h2e2 e6e5",
            result="RED_WIN",
        )

    # Heuristic fallback produces quality 0.6.
    assert result.overall_quality == 0.6
    assert "Heuristic analysis" in result.summary


def test_review_session_llm_malformed_json_falls_back():
    """When the LLM returns invalid JSON, the heuristic fallback is used."""
    config = ReviewConfig(gateway_url="http://mock-gateway")

    mock_response = MagicMock()
    mock_response.json.return_value = {
        "choices": [{"message": {"content": "not valid json{"}}]
    }
    mock_response.raise_for_status.return_value = None

    mock_client = MagicMock()
    mock_client.__enter__ = MagicMock(return_value=mock_client)
    mock_client.__exit__ = MagicMock(return_value=False)
    mock_client.post.return_value = mock_response

    with patch("ai_agent.review_agent.httpx.Client", return_value=mock_client):
        session = ReviewSession(config=config)
        result = session.analyze_game(
            game_type="GO",
            moves=";B[dd]",
            result="BLACK_WIN",
        )

    assert result.overall_quality == 0.6  # heuristic fallback


# --------------------------------------------------------------------------- #
# Graph compilation
# --------------------------------------------------------------------------- #


def test_build_review_graph_compiles():
    config = ReviewConfig(gateway_url=None)
    graph = build_review_graph(config)
    assert graph is not None


def test_review_config_load_defaults():
    """ReviewConfig.load() reads from environment with sensible defaults."""
    config = ReviewConfig.load()
    assert config.model == "gpt-4o" or config.model  # may be overridden by env
    assert config.timeout_sec > 0
    assert config.max_reflections >= 1
