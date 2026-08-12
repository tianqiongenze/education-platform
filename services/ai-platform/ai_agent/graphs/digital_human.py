"""Digital-human agent subgraph.

Flow: script -> tts -> lip_sync -> render -> stream
"""

from __future__ import annotations

from ..state import AgentState
from langgraph.graph import END, START, StateGraph


def script_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "script", "text": "Hello, student."}], "current_node": "script"}


def tts_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "tts", "audio": "bytes"}], "current_node": "tts"}


def lip_sync_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "lip_sync", "frames": 30}], "current_node": "lip_sync"}


def render_node(state: AgentState) -> dict:
    return {"artifacts": [{"stage": "render", "video": "bytes"}], "current_node": "render"}


def stream_node(state: AgentState) -> dict:
    return {"current_node": "stream", "final_answer": "digital-human stream ready"}


def build_digital_subgraph():
    graph = StateGraph(AgentState)
    for name, fn in [
        ("script", script_node),
        ("tts", tts_node),
        ("lip_sync", lip_sync_node),
        ("render", render_node),
        ("stream", stream_node),
    ]:
        graph.add_node(name, fn)

    graph.add_edge(START, "script")
    graph.add_edge("script", "tts")
    graph.add_edge("tts", "lip_sync")
    graph.add_edge("lip_sync", "render")
    graph.add_edge("render", "stream")
    graph.add_edge("stream", END)
    return graph.compile()
