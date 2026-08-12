package com.ep.grading.common;

/**
 * Shared, context-neutral snapshot of a plagiarism check result.
 *
 * <p>The {@code grading} bounded context consumes plagiarism outcomes through
 * this abstraction rather than importing {@code homework} entity classes
 * directly, keeping the two bounded contexts decoupled (D-136 context
 * isolation). The {@code homework} context's {@code PlagiarismService}
 * implementation maps its {@code PlagiarismCheck} entity into this record at
 * the boundary.
 */
public record PlagiarismCheckResult(
        Double similarityScore,
        Status status
) {

    /** Context-neutral plagiarism status mirror. */
    public enum Status {
        PENDING,
        CLEAN,
        FLAGGED,
        REVIEWING
    }

    /** Convenience: whether the result can be treated as a pass for scoring. */
    public boolean isPassing() {
        return status == Status.CLEAN || status == Status.REVIEWING;
    }
}
