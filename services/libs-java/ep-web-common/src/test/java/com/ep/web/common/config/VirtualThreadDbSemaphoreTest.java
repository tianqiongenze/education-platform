package com.ep.web.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link DbConnectionSemaphore} 与 {@link DbConnectionTemplate} 单元测试。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>正常获取并释放许可</li>
 *   <li>并发获取超过 permits 数量时阻塞</li>
 *   <li>带超时的获取，超时返回 false</li>
 *   <li>虚拟线程场景下大量并发获取不会压垮 Semaphore</li>
 * </ol>
 */
class VirtualThreadDbSemaphoreTest {

    /** 测试1：正常获取许可并释放，availablePermits 随之变化。 */
    @Test
    void acquireAndRelease_shouldUpdateAvailablePermits() throws InterruptedException {
        DbConnectionSemaphore semaphore = new DbConnectionSemaphore(3);
        assertEquals(3, semaphore.availablePermits());

        assertTrue(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS));
        assertEquals(2, semaphore.availablePermits());

        semaphore.release();
        assertEquals(3, semaphore.availablePermits());
    }

    /** 测试2：并发获取超过 permits 数量时，第 permits+1 个获取应阻塞（在超时内拿不到）。 */
    @Test
    void concurrentAcquireBeyondPermits_shouldBlockExtraAcquirer() throws Exception {
        int permits = 2;
        DbConnectionSemaphore semaphore = new DbConnectionSemaphore(permits);

        // 先由主线程占满全部 permits
        assertTrue(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS));
        assertTrue(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS));
        assertEquals(0, semaphore.availablePermits());

        // 另起一个线程尝试获取，应当被阻塞：在 200ms 超时内拿不到 permit
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = pool.submit(() -> semaphore.tryAcquire(200, TimeUnit.MILLISECONDS));
            long start = System.nanoTime();
            Boolean acquired = future.get(1, TimeUnit.SECONDS);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertFalse(acquired, "permits 已被占满，额外获取应超时返回 false");
            assertTrue(elapsedMs >= 150, "应等待接近超时时长才返回，实际 " + elapsedMs + "ms");
        } finally {
            pool.shutdownNow();
        }

        // 释放一个 permit 后，新获取应能立即成功
        semaphore.release();
        assertTrue(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS));
        semaphore.release();
        // 还原：再释放一个，恢复到初始 2 个可用
        semaphore.release();
        assertEquals(2, semaphore.availablePermits());
    }

    /** 测试3：带超时获取，超时返回 false 且不占用 permit。 */
    @Test
    void tryAcquire_whenPermitsExhausted_shouldReturnFalseOnTimeout() throws InterruptedException {
        DbConnectionSemaphore semaphore = new DbConnectionSemaphore(1);
        assertTrue(semaphore.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertEquals(0, semaphore.availablePermits());

        long start = System.nanoTime();
        boolean acquired = semaphore.tryAcquire(150, TimeUnit.MILLISECONDS);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(acquired, "permits 已耗尽，应超时返回 false");
        assertTrue(elapsedMs >= 100, "应阻塞约 150ms，实际 " + elapsedMs + "ms");
        // 超时未获取成功，不应占用 permit
        assertEquals(0, semaphore.availablePermits());

        semaphore.release();
        assertEquals(1, semaphore.availablePermits());
    }

    /**
     * 测试4：虚拟线程场景下大量并发获取不会压垮 Semaphore。
     *
     * <p>用 Java 21 虚拟线程发起 200 个并发任务，permits=10。
     * 断言：实际进入临界区的并发数永不超过 permits；全部任务最终都能完成。
     *
     * <p>D-139 调优说明：原 taskCount=1000 在 CI 环境（QEMU 模拟 arm64 JVM）
     * 上因虚拟线程调度抖动导致 5s/15s 内部分任务来不及获取 permit。
     * 降至 200 并发，在保证"并发不超过 permits"核心断言语义不变的前提下
     * 提升 CI 稳定性。
     *
     * <p>D-216 稳定性修复：原 30s 内层获取超时在 -T 1C 并行构建的高负载下
     * 被 JVM 停顿击穿（观测 30.10s 处超时）。内层超时不承载断言语义，仅作
     * 死锁保险，放宽至 100s，由外层 f.get(120s) 统一兜底；同时修复许可泄漏
     * ——tryAcquire 超时返回 false 时不再 release 未获取的 permit。
     */
    @Test
    void virtualThreadsLargeConcurrency_shouldNeverExceedPermits() throws Exception {
        int permits = 10;
        int taskCount = 200;
        DbConnectionSemaphore semaphore = new DbConnectionSemaphore(permits);

        // 记录任意时刻在临界区内的最大并发数
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);

        try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(taskCount);
            CountDownLatch allSubmitted = new CountDownLatch(1);

            for (int i = 0; i < taskCount; i++) {
                futures.add(vthreads.submit(() -> {
                    boolean acquired = false;
                    try {
                        acquired = semaphore.tryAcquire(100, TimeUnit.SECONDS);
                        assertTrue(acquired, "虚拟线程应在 100s 死锁保险窗口内拿到 permit");
                        int cur = inFlight.incrementAndGet();
                        // 记录历史峰值
                        maxInFlight.accumulateAndGet(cur, Math::max);
                        // 模拟极短的 DB 访问，确保并发叠加
                        Thread.sleep(1);
                        inFlight.decrementAndGet();
                        completed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (acquired) {
                            semaphore.release();
                        }
                    }
                    return null;
                }));
            }
            allSubmitted.countDown();

            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        assertEquals(taskCount, completed.get(), "所有虚拟线程任务都应完成");
        assertTrue(maxInFlight.get() <= permits,
                "临界区并发数不应超过 permits=" + permits + "，实际峰值=" + maxInFlight.get());
        assertTrue(maxInFlight.get() >= permits,
                "应至少出现过 permits 并发以验证限流生效，实际峰值=" + maxInFlight.get());
        assertEquals(permits, semaphore.availablePermits(),
                "全部释放后 permits 应恢复初始值");
    }

    /** 测试5：DbConnectionTemplate 在 permit 耗尽时抛出 DbAcquireTimeoutException。 */
    @Test
    void executeWithPermit_whenTimeout_shouldThrowDbAcquireTimeoutException() throws InterruptedException {
        DbConnectionSemaphore semaphore = new DbConnectionSemaphore(1);
        // 占用唯一 permit
        assertTrue(semaphore.tryAcquire(50, TimeUnit.MILLISECONDS));

        DbConnectionTemplate template = new DbConnectionTemplate(semaphore, 100);
        assertThrows(DbAcquireTimeoutException.class,
                () -> template.executeWithPermit(() -> "should-not-run"));

        // 释放后应能正常执行
        semaphore.release();
        String result = template.executeWithPermit(() -> "ok");
        assertEquals("ok", result);
        assertEquals(1, semaphore.availablePermits());
    }

    /** 测试6：DbConnectionTemplate 在任务抛异常时仍释放 permit。 */
    @Test
    void executeWithPermit_whenTaskThrows_shouldReleasePermit() {
        DbConnectionSemaphore semaphore = new DbConnectionSemaphore(1);
        DbConnectionTemplate template = new DbConnectionTemplate(semaphore, 1000);

        assertThrows(RuntimeException.class,
                () -> template.executeWithPermit(() -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals(1, semaphore.availablePermits(),
                "任务异常后 permit 应被释放");
    }
}
