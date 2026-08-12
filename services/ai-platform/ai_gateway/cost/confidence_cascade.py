"""SAD §5 Layer 3 — Confidence-based cascade.

After the small model answers, the cascade inspects the response for low-
confidence signals. If any are present, the caller escalates to the flagship
model (Layer 4 fallback runs against the flagship provider chain). High-
confidence small-model answers are returned as-is, capturing the cost saving.

Pure Python — no external dependencies.
"""

from __future__ import annotations

from ..models import ChatResponse

# Responses shorter than this (chars of assistant content) are low-confidence.
_SHORT_RESPONSE_MIN_CHARS = 50

# Substrings that signal low confidence / hedging.
_LOW_CONFIDENCE_PHRASES = (
    "i'm not sure",
    "i don't know",
    "i dont know",
    "cannot",
    "can't",
    "unable to",
    "i apologize",
    "i'm sorry",
    "sorry, but",
)

# A response that is mostly an apology is low-confidence. We detect this by
# the apology phrases above appearing near the start of the content.
_APOLOGY_WINDOW_CHARS = 60


class ConfidenceCascade:
    """Layer 3 — decide whether a small-model response should escalate."""

    @staticmethod
    def _assistant_content(response: ChatResponse) -> str:
        if not response.choices:
            return ""
        try:
            return response.choices[0].get("message", {}).get("content", "") or ""
        except AttributeError:  # pragma: no cover — defensive for non-dict shapes
            return ""

    def evaluate(self, response: ChatResponse) -> dict:
        """Return ``{"confidence": "high"|"low", "reasons": list[str]}``."""
        content = self._assistant_content(response)
        reasons: list[str] = []

        if len(content) < _SHORT_RESPONSE_MIN_CHARS:
            reasons.append(f"response_too_short({len(content)}<{_SHORT_RESPONSE_MIN_CHARS})")

        lowered = content.lower()
        for phrase in _LOW_CONFIDENCE_PHRASES:
            if phrase in lowered:
                reasons.append(f"low_confidence_phrase:{phrase}")
                break  # one phrase is enough; keep reasons concise

        # Trailing question mark ⇒ hedging / re-asking the user.
        if content.rstrip().endswith("?"):
            reasons.append("ends_with_question_mark")

        # Apology-dominant: apology phrase appears in the opening window.
        head = lowered[:_APOLOGY_WINDOW_CHARS]
        if any(p in head for p in ("i apologize", "i'm sorry", "sorry, but")):
            reasons.append("apology_dominant")

        confidence = "low" if reasons else "high"
        return {"confidence": confidence, "reasons": reasons}

    def should_escalate(self, response: ChatResponse) -> bool:
        """Return True when the response shows low confidence → escalate."""
        return self.evaluate(response)["confidence"] == "low"
