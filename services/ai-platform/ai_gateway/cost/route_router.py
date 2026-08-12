"""SAD §5 Layer 2 — RouteLLM-style intent router.

Classifies a prompt as ``simple`` or ``complex`` using cheap heuristics and
routes it to the appropriate model family. Simple prompts go to a small
(economical) model; complex prompts go straight to the flagship model,
skipping the cascade entirely (SAD §5, irreversible order).

Pure Python — no external dependencies, no I/O.
"""

from __future__ import annotations

from ..models import ChatMessage

# Model names referenced by the router. These match the OpenAI family naming
# convention used elsewhere in the gateway.
SMALL_MODEL = "gpt-4o-mini"
FLAGSHIP_MODEL = "gpt-4o"

# Heuristic thresholds / keyword sets.
_SHORT_PROMPT_MAX_CHARS = 100
_COMPLEX_KEYWORDS = (
    "explain",
    "analyze",
    "design",
    "architect",
    "implement",
    "refactor",
)


class RouteRouter:
    """Layer 2 — classify prompt intent and pick a model."""

    @staticmethod
    def _last_user_content(messages: list[ChatMessage]) -> str:
        for msg in reversed(messages):
            if msg.role == "user":
                return msg.content
        return ""

    def classify(self, messages: list[ChatMessage]) -> str:
        """Return ``"simple"`` or ``"complex"`` for the given messages."""
        prompt = self._last_user_content(messages)
        if not prompt:
            # No user content to route on — treat as complex so the flagship
            # model handles it rather than an under-powered small model.
            return "complex"

        # Long prompts are complex regardless of keywords.
        if len(prompt) >= _SHORT_PROMPT_MAX_CHARS:
            return "complex"
        # Markdown code fences indicate code — complex.
        if "```" in prompt:
            return "complex"
        # Keyword trigger — complex.
        lowered = prompt.lower()
        if any(kw in lowered for kw in _COMPLEX_KEYWORDS):
            return "complex"
        return "simple"

    def route(self, messages: list[ChatMessage]) -> str:
        """Return the model name chosen for ``messages``."""
        return SMALL_MODEL if self.classify(messages) == "simple" else FLAGSHIP_MODEL
