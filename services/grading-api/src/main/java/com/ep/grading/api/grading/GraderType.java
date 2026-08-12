package com.ep.grading.api.grading;

import java.io.Serializable;

/**
 * Identifies who/what produced a {@link GradingResult}.
 *
 * <ul>
 *   <li>{@code AI} — subjective answer graded via the sp04 AI grading service
 *       (RAG + LLM rubric scoring). Objective questions are never tagged AI;
 *       they use exact-match scoring and are tagged {@code MANUAL}.</li>
 *   <li>{@code MANUAL} — objective exact-match grading, or a human-graded
 *       fallback when the AI stage is unavailable / disabled for the tenant.</li>
 * </ul>
 *
 * <p>Iron Law 7: enum + {@link Serializable}, ordinal-stable; do not reorder
 * existing constants (GraalVM AOT serializes enums by name, but consumers
 * may switch on ordinal — keep additions append-only).
 */
public enum GraderType implements Serializable {
    AI,
    MANUAL
}
