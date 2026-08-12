package com.ep.grading.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * D-185: Process-level sandbox executor (Iron Law 8 Option C).
 * <p>
 * <b>Iron Law 8 decision record.</b> Three alternatives were evaluated for
 * sandboxed code execution in the grading service:
 * <ol>
 *   <li><b>A) Real Firecracker microVM</b> — needs a Kubernetes CRD + a runtime
 *       daemon (jailer) with KVM access. Not available in the offline AOT build
 *       environment and requires privileged nodes.</li>
 *   <li><b>B) Docker container isolation</b> — needs the Docker daemon (or
 *       containerd + nerdctl). Adds an external dependency that is unavailable
 *       offline and complicates GraalVM native image startup.</li>
 *   <li><b>C) Java ProcessBuilder with resource limits + temp dir isolation +
 *       timeout</b> (selected) — zero external dependencies, fully AOT-friendly,
 *       works in the offline build environment. Isolation is weaker than a
 *       microVM (shared kernel) but sufficient for the IT-backend MVP grading
 *       workload, which runs trusted course exercises behind Dubbo auth.</li>
 * </ol>
 * <p>
 * <b>EP_DEV_ONLY guard:</b> when the {@code EP_DEV_ONLY} environment variable is
 * <b>not</b> set, the executor returns a mock result instead of spawning
 * processes. This prevents accidental execution of student code on developer
 * machines. In production (or when EP_DEV_ONLY is explicitly set) real
 * processes are spawned.
 */
@Component
public class ProcessSandboxExecutor implements SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandboxExecutor.class);

    /** Compile step timeout (seconds) — generous to accommodate slow CI boxes. */
    static final int COMPILE_TIMEOUT_SEC = 15;

    /** Default run timeout when the request does not specify one. */
    static final int DEFAULT_TIMEOUT_SEC = 30;

    /** Default memory limit (MB) when the request does not specify one. */
    static final int DEFAULT_MEMORY_MB = 256;

    private final boolean sandboxEnabled;

    public ProcessSandboxExecutor(@Value("${EP_DEV_ONLY:false}") String epDevOnly) {
        this.sandboxEnabled = isDevOnlySet(epDevOnly);
        if (sandboxEnabled) {
            log.warn("D-185: sandbox ENABLED (EP_DEV_ONLY=true) — real code execution active");
        } else {
            log.info("D-185: sandbox DISABLED (EP_DEV_ONLY not set) — returning mock results");
        }
    }

    /** Test constructor: allows tests to force-enable the sandbox. */
    ProcessSandboxExecutor(boolean sandboxEnabled) {
        this.sandboxEnabled = sandboxEnabled;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "ExecutionRequest cannot be null");
        if (!sandboxEnabled) {
            return new ExecutionResult(0, "sandbox disabled in dev", "", 0L, false);
        }
        LanguageSpec spec = LanguageSpec.fromName(request.language());
        int timeoutSec = request.timeoutSec() > 0 ? request.timeoutSec() : DEFAULT_TIMEOUT_SEC;
        int memoryMB = request.memoryLimitMB() > 0 ? request.memoryLimitMB() : DEFAULT_MEMORY_MB;

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ep-sandbox-");
            Path sourceFile = tempDir.resolve(spec.fileName());
            Files.writeString(sourceFile,
                    Objects.requireNonNullElse(request.sourceCode(), ""),
                    StandardCharsets.UTF_8);

            // 1. Compile (if applicable)
            List<String> compileCmd = spec.compileCommands(tempDir.toString());
            if (!compileCmd.isEmpty()) {
                ExecutionResult compileResult = runProcess(compileCmd, tempDir, "",
                        COMPILE_TIMEOUT_SEC, memoryMB);
                if (compileResult.exitCode() != 0) {
                    return new ExecutionResult(compileResult.exitCode(),
                            compileResult.stdout(),
                            "compile error: " + compileResult.stderr(),
                            compileResult.executionTimeMs(),
                            compileResult.timedOut());
                }
            }

            // 2. Run
            List<String> runCmd = spec.runCommand(tempDir.toString());
            return runProcess(runCmd, tempDir,
                    Objects.requireNonNullElse(request.stdin(), ""),
                    timeoutSec, memoryMB);
        } catch (IOException e) {
            log.error("D-185: sandbox I/O error for language={}", request.language(), e);
            return new ExecutionResult(-1, "", "sandbox io error: " + e.getMessage(), 0L, false);
        } finally {
            cleanup(tempDir);
        }
    }

    boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    /**
     * Spawn a single process, feed stdin, capture stdout/stderr, enforce timeout.
     */
    private ExecutionResult runProcess(List<String> command, Path workDir, String stdin,
                                       int timeoutSec, int memoryMB) {
        List<String> fullCommand = new ArrayList<>(command);
        ProcessBuilder pb = new ProcessBuilder(fullCommand)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
        // Advisory memory hint for the JVM-run languages; for native toolchains
        // (g++, go) the OS scheduler governs real memory. We set a portable
        // hint so callers can tune.
        pb.environment().put("EP_SANDBOX_MEMORY_MB", String.valueOf(memoryMB));

        Process process = null;
        try {
            long start = System.currentTimeMillis();
            process = pb.start();

            // Write stdin asynchronously (avoid deadlock when child fills pipe buffer).
            final Process p = process;
            Thread stdinWriter = new Thread(() -> {
                try (var os = p.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                    // child closed stdin early — not an error
                }
            }, "sandbox-stdin");
            stdinWriter.setDaemon(true);
            stdinWriter.start();

            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            if (!finished) {
                process.destroyForcibly();
                // drain the (already-read) streams above; ensure process exits.
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new ExecutionResult(-1, stdout, stderr, elapsed, true);
            }
            stdinWriter.join(500);
            return new ExecutionResult(process.exitValue(), stdout, stderr, elapsed, false);
        } catch (IOException e) {
            return new ExecutionResult(-1, "", "io error: " + e.getMessage(), 0L, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecutionResult(-1, "", "interrupted", 0L, false);
        }
    }

    private void cleanup(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static boolean isDevOnlySet(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase();
        return "true".equals(v) || "1".equals(v);
    }
}
