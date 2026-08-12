"""AI gateway cost stack — 4-layer irreversible-order cost optimization.

Implements SDD §3.4 / SAD §5 cost stack:
    ① semantic cache → ② RouteLLM intent route → ③ confidence cascade → ④ provider fallback.
The order is irreversible (Iron Law): a cache hit returns immediately and
never enters routing; routing decides the model before cascade; cascade only
escalates low-confidence small-model outputs to the flagship model; fallback
chains providers last.
"""
