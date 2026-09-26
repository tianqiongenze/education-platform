package com.ep.web.common.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/**
 * D-216 / P1-8: {@link AtomicSnowflakeIdGenerator} 单元测试。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>并发 10 万次发号无重复（无锁 CAS 正确性）</li>
 *   <li>位布局与各服务现有 synchronized 拷贝语义兼容（同 EPOCH / 位移 / 位宽）</li>
 *   <li>跨实例不同 workerId 同毫秒发号不冲突</li>
 *   <li>时钟回拨保护：小于阈值等待追平；大于阈值抛异常且状态不被破坏</li>
 *   <li>workerId 解析：系统属性 / 环境变量 / 默认值 / 越界快速失败</li>
 * </ol>
 */
class AtomicSnowflakeIdGeneratorTest {

    /** 可注入的可控时钟（D-216：为回拨测试提供确定性时间源）。 */
    private static final class MutableClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong();

        MutableClock(long initialMillis) {
            now.set(initialMillis);
        }

        @Override
        public long getAsLong() {
            return now.get();
        }

        void set(long millis) {
            now.set(millis);
        }
    }

    /**
     * 按读取次数推进的时钟：每 {@code readsPerMillis} 次读取前进 1ms。
     * 单线程下每个 nextId 至少读 1 次，序号耗尽后的自旋会快速把读数推过阈值翻毫秒。
     */
    private static final class CallCountClock implements LongSupplier {
        private final AtomicLong reads = new AtomicLong();
        private final long base;
        private final long readsPerMillis;

        CallCountClock(long baseMillis, long readsPerMillis) {
            this.base = baseMillis;
            this.readsPerMillis = readsPerMillis;
        }

        @Override
        public long getAsLong() {
            return base + reads.incrementAndGet() / readsPerMillis;
        }
    }

    /**
     * 测试1：8 线程并发共发 10 万个 ID，无任何重复。
     *
     * <p>使用 CallCountClock（按读取次数推进）而非真实系统时钟，避免高负载下
     * OS 调度抖动导致时钟回拨误报。Snowflake 的并发正确性取决于 CAS
     * 自旋逻辑——序号耗尽时推进逻辑时间戳即可。CallCountClock 保证
     * 单调递增，逻辑时间戳永远 ≤ 时钟读数，不会触发回拨保护。
     */
    @Test
    void uniqueness_100k_concurrent_noDuplicates() throws Exception {
        // 按 1000 次读取前进 1ms：100K 个 ID 远超单毫秒 4096 序号上限，
        // 逻辑时间戳会推进，但时钟读数始终 ≥ 逻辑时间戳（单调递增）
        CallCountClock clock = new CallCountClock(AtomicSnowflakeIdGenerator.EPOCH + 50_000, 1000);
        AtomicSnowflakeIdGenerator gen = new AtomicSnowflakeIdGenerator(
                7, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, clock);
        int threads = 8;
        int perThread = 12_500;
        ConcurrentLinkedQueue<Long> ids = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(gen.nextId());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(); // 传播线程内异常
            }
        }

        assertEquals(threads * perThread, ids.size(), "总发号数应为 100000");
        Set<Long> distinct = new HashSet<>(ids);
        assertEquals(threads * perThread, distinct.size(), "10 万个 ID 中出现重复");
    }

    /**
     * 测试2：位布局与现有服务拷贝（boardgame/core-business/senior-care）语义兼容 —
     * 硬编码期望值防止位移/纪元漂移破坏跨服务 ID 解读。
     */
    @Test
    void bitLayout_matchesLegacyServiceCopies() {
        assertEquals(1704067200000L, AtomicSnowflakeIdGenerator.EPOCH, "EPOCH 必须保持 2024-01-01 UTC");
        assertEquals(10L, AtomicSnowflakeIdGenerator.WORKER_ID_BITS);
        assertEquals(12L, AtomicSnowflakeIdGenerator.SEQUENCE_BITS);
        assertEquals(22L, AtomicSnowflakeIdGenerator.TIMESTAMP_SHIFT);
        assertEquals(12L, AtomicSnowflakeIdGenerator.WORKER_ID_SHIFT);

        long ts = AtomicSnowflakeIdGenerator.EPOCH + 123_456;
        AtomicSnowflakeIdGenerator gen = new AtomicSnowflakeIdGenerator(
                3, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, new MutableClock(ts));

        long first = gen.nextId();
        long second = gen.nextId();

        assertEquals(ts, AtomicSnowflakeIdGenerator.EPOCH + (first >>> 22), "时间戳位解码");
        assertEquals(3L, (first >>> 12) & 0x3FF, "workerId 位解码");
        assertEquals(0L, first & 0xFFF, "同毫秒首个序列号应为 0");
        assertEquals(1L, second & 0xFFF, "同毫秒第二个序列号应为 1");
        assertEquals(first + 1, second, "同一实例同毫秒内 ID 连续递增");
        assertTrue(first > 0, "纪元之后生成的 ID 应为正数");
    }

    /**
     * 测试3：跨实例 workerId 不同 — 两实例在完全相同的毫秒内各发满 4096 个序号，
     * 集合互不相交（workerId 位保证跨实例不冲突）。
     */
    @Test
    void crossInstance_differentWorkerIds_neverCollide() {
        long frozenTs = AtomicSnowflakeIdGenerator.EPOCH + 999;
        MutableClock clock = new MutableClock(frozenTs);
        AtomicSnowflakeIdGenerator genA = new AtomicSnowflakeIdGenerator(
                1, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, clock);
        AtomicSnowflakeIdGenerator genB = new AtomicSnowflakeIdGenerator(
                2, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, clock);

        Set<Long> idsA = new HashSet<>();
        Set<Long> idsB = new HashSet<>();
        for (int i = 0; i < 4096; i++) {
            idsA.add(genA.nextId());
            idsB.add(genB.nextId());
        }

        assertEquals(4096, idsA.size(), "单实例单毫秒应发满 4096 个不重复 ID");
        assertEquals(4096, idsB.size(), "单实例单毫秒应发满 4096 个不重复 ID");
        for (long id : idsA) {
            assertTrue(!idsB.contains(id), "不同 workerId 的实例间出现 ID 冲突: " + id);
        }
        // workerId 位解码各归其位
        for (long id : idsA) {
            assertEquals(1L, (id >>> 12) & 0x3FF);
        }
        for (long id : idsB) {
            assertEquals(2L, (id >>> 12) & 0x3FF);
        }
    }

    /**
     * 测试4：时钟小幅回拨（&le; 阈值）时等待时钟追平，而不是抛异常或发出回退时间戳的 ID。
     * 后台线程 100ms 后把可控时钟推回目标时间，nextId 应阻塞等待后成功发号。
     */
    @Test
    void clockBackward_withinTolerance_waitsForClockToCatchUp() throws Exception {
        MutableClock clock = new MutableClock(AtomicSnowflakeIdGenerator.EPOCH + 10_000);
        AtomicSnowflakeIdGenerator gen = new AtomicSnowflakeIdGenerator(
                5, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, clock);

        long id1 = gen.nextId();

        // 回拨 3ms（阈值 5ms），另起线程在 100ms 后把时钟推进到已发号时间戳之后
        clock.set(AtomicSnowflakeIdGenerator.EPOCH + 9_997);
        Thread advancer = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            clock.set(AtomicSnowflakeIdGenerator.EPOCH + 10_001);
        });
        advancer.setDaemon(true);
        advancer.start();

        long id2 = assertTimeoutPreemptively(Duration.ofSeconds(5), gen::nextId);

        assertNotEquals(id1, id2);
        long ts1 = AtomicSnowflakeIdGenerator.EPOCH + (id1 >>> 22);
        long ts2 = AtomicSnowflakeIdGenerator.EPOCH + (id2 >>> 22);
        assertTrue(ts2 >= ts1, "回拨恢复后发出的 ID 时间戳不得早于此前: " + ts2 + " < " + ts1);
    }

    /**
     * 测试5：时钟大幅回拨（&gt; 阈值）抛 {@link IllegalStateException}，
     * 且状态未被破坏 —— 时钟恢复后可继续发号且不与已发 ID 重复。
     */
    @Test
    void clockBackward_beyondTolerance_throwsAndStateStaysUsable() {
        MutableClock clock = new MutableClock(AtomicSnowflakeIdGenerator.EPOCH + 20_000);
        AtomicSnowflakeIdGenerator gen = new AtomicSnowflakeIdGenerator(
                5, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, clock);

        long id1 = gen.nextId();

        clock.set(AtomicSnowflakeIdGenerator.EPOCH + 19_990); // 回拨 10ms > 阈值 5ms
        IllegalStateException ex = assertThrows(IllegalStateException.class, gen::nextId);
        assertTrue(ex.getMessage().contains("backwards"), "异常信息应说明时钟回拨: " + ex.getMessage());

        // 时钟恢复后继续发号，ID 不重复
        clock.set(AtomicSnowflakeIdGenerator.EPOCH + 20_001);
        long id2 = gen.nextId();
        assertNotEquals(id1, id2);
        assertEquals(0L, id2 & 0xFFF, "新毫秒首个序列号应为 0");
    }

    /** 测试6：单毫秒序号耗尽（4096 个）后自旋等待下一毫秒继续发号，全程无重复。 */
    @Test
    void sequenceOverflow_spinsToNextMillis() {
        // 每 8192 次时钟读取前进 1ms：单线程 4096 个 ID 后必然触发一次序号溢出翻毫秒
        CallCountClock clock = new CallCountClock(AtomicSnowflakeIdGenerator.EPOCH + 30_000, 8192);
        AtomicSnowflakeIdGenerator gen = new AtomicSnowflakeIdGenerator(
                9, AtomicSnowflakeIdGenerator.DEFAULT_MAX_BACKWARD_MS, clock);

        long prevTs = -1;
        Set<Long> distinctTimestamps = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 8192; i++) {
            long id = gen.nextId();
            assertTrue(ids.add(id), "序号翻毫秒过程中出现重复 ID");
            long ts = AtomicSnowflakeIdGenerator.EPOCH + (id >>> 22);
            assertTrue(ts >= prevTs, "时间戳不得回退");
            prevTs = ts;
            distinctTimestamps.add(ts);
        }
        assertTrue(distinctTimestamps.size() >= 2, "8192 个 ID 必然跨越多个毫秒（序号位仅 4096）");
    }

    /** 测试7：workerId 解析 —— 系统属性优先、非法值回退默认、越界快速失败。 */
    @Test
    void workerId_resolution_propertyInvalidAndBoundary() {
        String oldProp = System.getProperty("ep.worker.id");
        try {
            // 合法系统属性被采纳
            System.setProperty("ep.worker.id", "42");
            assertEquals(42L, AtomicSnowflakeIdGenerator.resolveWorkerId());

            // 非法值回退默认 1（环境变量 EP_WORKER_ID 未设置时）
            System.setProperty("ep.worker.id", "not-a-number");
            long resolved = AtomicSnowflakeIdGenerator.resolveWorkerId();
            String env = System.getenv("EP_WORKER_ID");
            long expected = 1L;
            if (env != null && !env.isBlank()) {
                try {
                    expected = Long.parseLong(env.trim());
                } catch (NumberFormatException ignored) {
                    // 环境变量本身非法时同样回退默认 1
                }
            }
            assertEquals(expected, resolved, "非法系统属性应回退到 EP_WORKER_ID 或默认 1");

            // 显式构造越界快速失败
            assertThrows(IllegalArgumentException.class, () -> new AtomicSnowflakeIdGenerator(-1));
            assertThrows(IllegalArgumentException.class, () -> new AtomicSnowflakeIdGenerator(1024));
            assertEquals(1023L, new AtomicSnowflakeIdGenerator(1023).getWorkerId(), "边界值 1023 合法");
        } finally {
            if (oldProp == null) {
                System.clearProperty("ep.worker.id");
            } else {
                System.setProperty("ep.worker.id", oldProp);
            }
        }
    }
}
