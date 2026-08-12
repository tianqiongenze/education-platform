package com.ep.grading.sandbox.judge0;

import com.ep.grading.sandbox.ExecutionRequest;
import com.ep.grading.sandbox.ExecutionResult;
import com.ep.grading.sandbox.SandboxExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-185: Judge0Worker tests — the worker is disabled by default so the polling
 * loop never starts. The end-to-end path is exercised with the worker enabled
 * and a mocked queue + sandbox executor.
 */
class Judge0WorkerTest {

    private Judge0Queue queue;
    private SandboxExecutor sandboxExecutor;

    @BeforeEach
    void setUp() {
        queue = mock(Judge0Queue.class);
        sandboxExecutor = mock(SandboxExecutor.class);
    }

    @Test
    void disabled_worker_does_not_set_running_flag() {
        Judge0Worker worker = new Judge0Worker(queue, sandboxExecutor, false);
        worker.start();
        assertFalse(worker.isRunning());
        worker.stop();
    }

    @Test
    void stop_when_disabled_is_noop() {
        Judge0Worker worker = new Judge0Worker(queue, sandboxExecutor, false);
        worker.start();
        worker.stop(); // should not throw
    }

    @Test
    void enabled_worker_starts_and_stops_cleanly() throws Exception {
        when(queue.deblock(any(Long.class))).thenReturn(Optional.empty());
        Judge0Worker worker = new Judge0Worker(queue, sandboxExecutor, true);
        worker.start();
        // give the virtual thread a moment to flip the running flag
        Thread.sleep(100);
        assertTrue(worker.isRunning());
        worker.stop();
        assertFalse(worker.isRunning());
    }

    @Test
    void enabled_worker_processes_a_task_end_to_end() throws Exception {
        Judge0Task task = new Judge0Task("tok-7", "PYTHON", "print('hi')", "", 5);
        ExecutionResult execResult = new ExecutionResult(0, "hi\n", "", 5L, false);

        when(queue.deblock(any(Long.class)))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.empty());
        when(sandboxExecutor.execute(any(ExecutionRequest.class)))
                .thenReturn(execResult);

        Judge0Worker worker = new Judge0Worker(queue, sandboxExecutor, true);
        worker.start();
        // allow the virtual thread to process the task
        Thread.sleep(200);
        worker.stop();

        verify(sandboxExecutor, atLeastOnce()).execute(any(ExecutionRequest.class));
        verify(queue, atLeastOnce()).storeResult(any(String.class), any(Judge0Result.class));
    }
}
