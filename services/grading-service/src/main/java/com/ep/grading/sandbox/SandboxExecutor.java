package com.ep.grading.sandbox;

/**
 * D-185: Executes untrusted code inside an isolated sandbox.
 * <p>
 * Iron Law 8 selected Option C (Java ProcessBuilder) — see
 * {@link ProcessSandboxExecutor} for the production implementation. The
 * interface keeps callers decoupled from the sandboxing mechanism so a future
 * Firecracker/Docker backend (Option A/B) can be swapped in without touching
 * {@code GradingServiceImpl}.
 */
public interface SandboxExecutor {

    /**
     * Compile and run the code described by {@code request} inside a fresh
     * isolated process, capturing output and enforcing the timeout.
     *
     * @param request the execution request (non-null)
     * @return the execution result (never null)
     */
    ExecutionResult execute(ExecutionRequest request);
}
