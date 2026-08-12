package com.ep.grading.common;

/**
 * Context-neutral facade for plagiarism checks, owned by neither the
 * {@code homework} nor the {@code grading} bounded context.
 *
 * <p>The {@code grading} context depends on this shared abstraction instead of
 * the {@code homework}-context {@code PlagiarismService} so the two bounded
 * contexts stay decoupled (D-136 context isolation). The {@code homework}
 * context's {@code PlagiarismServiceImpl} additionally implements this
 * interface and maps its {@code PlagiarismCheck} entity into the
 * {@link PlagiarismCheckResult} record at the boundary.
 */
public interface PlagiarismCheckFacade {

    /**
     * Run (or look up) a plagiarism check for the given submission and return
     * a context-neutral snapshot of the result.
     *
     * @param submissionId the submission to check
     * @return the check result, or {@code null} if no content could be checked
     */
    PlagiarismCheckResult checkResult(Long submissionId);
}
