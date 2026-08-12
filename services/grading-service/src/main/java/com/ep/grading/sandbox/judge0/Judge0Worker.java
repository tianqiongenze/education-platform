package com.ep.grading.sandbox.judge0;

import com.ep.grading.sandbox.ExecutionRequest;
import com.ep.grading.sandbox.SandboxExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * D-185: Background worker that drains the {@link Judge0Queue}, runs each task
 * through the {@link SandboxExecutor}, and stores the result back in Redis.
 * <p>
 * The worker uses virtual threads (Iron Law 7) for the polling loop. It is
 * disabled by default and only starts when {@code judge0.worker.enabled=true}
 * to avoid spawning background threads during unit tests.
 */
@Component
public class Judge0Worker {

    private static final Logger log = LoggerFactory.getLogger(Judge0Worker.class);

    private final Judge0Queue queue;
    private final SandboxExecutor sandboxExecutor;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public Judge0Worker(Judge0Queue queue,
                        SandboxExecutor sandboxExecutor,
                        @Value("${judge0.worker.enabled:false}") boolean enabled) {
        this.queue = queue;
        this.sandboxExecutor = sandboxExecutor;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("D-185: Judge0Worker disabled (judge0.worker.enabled=false)");
            return;
        }
        running.set(true);
        // D-039: virtual threads for the polling loop.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(this::pollLoop);
        log.info("D-185: Judge0Worker started");
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("D-185: Judge0Worker stopped");
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                var task = queue.deblock(5L);
                if (task.isEmpty()) {
                    continue;
                }
                Judge0Task t = task.get();
                log.debug("D-185: processing task {}", t.token());
                ExecutionRequest request = new ExecutionRequest(
                        t.language(), t.sourceCode(), t.stdin(), t.timeoutSec(), 256);
                var result = sandboxExecutor.execute(request);
                queue.storeResult(t.token(), new Judge0Result(t.token(), result));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("D-185: Judge0Worker loop error", e);
            }
        }
    }

    /** Test visibility. */
    boolean isRunning() {
        return running.get();
    }
}
