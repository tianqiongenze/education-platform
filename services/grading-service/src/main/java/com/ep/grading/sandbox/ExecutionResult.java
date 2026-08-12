package com.ep.grading.sandbox;

import java.io.Serializable;

/**
 * D-185: Result of a sandbox code execution.
 *
 * @param exitCode         process exit code (0 = success). When the process
 *                         timed out, exitCode is set to -1 (or the forced kill
 *                         code) and {@link #timedOut} is true.
 * @param stdout           captured standard output (UTF-8)
 * @param stderr           captured standard error (UTF-8)
 * @param executionTimeMs  wall-clock execution time of the run step in ms
 * @param timedOut         true when the process was forcibly killed for timeout
 */
public record ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        long executionTimeMs,
        boolean timedOut
) implements Serializable {
}
