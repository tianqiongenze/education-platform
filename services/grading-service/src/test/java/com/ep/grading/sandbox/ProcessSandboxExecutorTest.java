package com.ep.grading.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-185: ProcessSandboxExecutor tests.
 * <p>
 * The EP_DEV_ONLY-guarded tests run only when the {@code EP_DEV_ONLY} env var
 * is set, because the executor refuses to spawn real processes otherwise (Iron
 * Law 0 / sandbox safety guard). The dev-mode-fallback test always runs.
 */
class ProcessSandboxExecutorTest {

    private ProcessSandboxExecutor executor;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ep-sandbox-test-");
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) { }
        }
    }

    @Test
    void sandbox_disabled_returns_mock_result_when_dev_only_not_set() {
        // Force-disabled executor
        executor = new ProcessSandboxExecutor(false);
        ExecutionResult result = executor.execute(
                new ExecutionRequest("PYTHON", "print('hello')", "", 5, 64));
        assertEquals(0, result.exitCode());
        assertEquals("sandbox disabled in dev", result.stdout());
        assertFalse(result.timedOut());
    }

    @Test
    void unsupported_language_throws() {
        // Use sandboxEnabled=true so the executor reaches LanguageSpec.fromName
        // (when disabled, it short-circuits with a mock result before validation).
        executor = new ProcessSandboxExecutor(true);
        try {
            executor.execute(new ExecutionRequest("COBOL", "display 'hi'", "", 5, 64));
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("COBOL"));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EP_DEV_ONLY", matches = "true|1")
    void python_echo_hello_prints_hello() {
        executor = new ProcessSandboxExecutor(true);
        ExecutionResult result = executor.execute(
                new ExecutionRequest("PYTHON", "print('hello')", "", 5, 64));
        assertEquals(0, result.exitCode(), "stderr=" + result.stderr());
        assertTrue(result.stdout().trim().contains("hello"));
        assertFalse(result.timedOut());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EP_DEV_ONLY", matches = "true|1")
    void infinite_loop_times_out() {
        executor = new ProcessSandboxExecutor(true);
        ExecutionResult result = executor.execute(
                new ExecutionRequest("PYTHON", "while True:\n    pass", "", 2, 64));
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EP_DEV_ONLY", matches = "true|1")
    void compile_error_reports_stderr() {
        executor = new ProcessSandboxExecutor(true);
        ExecutionResult result = executor.execute(
                new ExecutionRequest("PYTHON", "this is not valid python (", "", 5, 64));
        // python3 returns non-zero on syntax error
        assertTrue(result.exitCode() != 0);
        assertFalse(result.stderr().isBlank());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EP_DEV_ONLY", matches = "true|1")
    void temp_dir_cleaned_up_after_execution() throws IOException {
        executor = new ProcessSandboxExecutor(true);
        ExecutionResult result = executor.execute(
                new ExecutionRequest("PYTHON", "print('cleanup')", "", 5, 64));
        assertNotNull(result);
        // The temp dir used internally should be gone — we can't observe the
        // internal dir directly, but verifying the executor doesn't throw on
        // repeated invocations is the proxy assertion.
        ExecutionResult second = executor.execute(
                new ExecutionRequest("PYTHON", "print('again')", "", 5, 64));
        assertEquals(0, second.exitCode(), "stderr=" + second.stderr());
    }
}
