package com.ep.grading.sandbox;

import java.io.Serializable;

/**
 * D-185: Request payload for a sandbox code execution.
 * <p>
 * Iron Law 8 decision (Option C — Java ProcessBuilder): this record is the
 * transport contract between callers (GradingService, Judge0 worker) and
 * {@link SandboxExecutor}. It carries everything the executor needs to compile
 * and run untrusted source code in an isolated process.
 *
 * @param language       one of the {@link LanguageSpec} names (JAVA, PYTHON, GO, CPP)
 * @param sourceCode     the raw source code to compile and execute
 * @param stdin          standard input fed to the running program (may be empty)
 * @param timeoutSec     wall-clock execution timeout; the process is
 *                       destroyForcibly'd if exceeded (default 30 when <= 0)
 * @param memoryLimitMB  advisory memory limit (JVM heap hints; process-level
 *                       enforcement is OS-dependent). Default 256 when <= 0.
 */
public record ExecutionRequest(
        String language,
        String sourceCode,
        String stdin,
        int timeoutSec,
        int memoryLimitMB
) implements Serializable {
}
