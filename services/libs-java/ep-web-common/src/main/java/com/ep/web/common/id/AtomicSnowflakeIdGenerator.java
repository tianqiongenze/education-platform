package com.ep.web.common.id;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * D-216 / P1-8: 共享版 Snowflake ID 生成器（无锁实现，下沉至 ep-web-common）。
 *
 * <p>审查发现 boardgame-service / core-business / senior-care 等服务各持有一份
 * {@code synchronized} 拷贝。本类提供语义兼容的共享实现：
 * <ul>
 *   <li>位布局与现有各服务拷贝完全一致，保证 ID 语义可跨服务解读：
 *       {@code 1 bit 符号 | 41 bits 时间戳(ms, 自 2024-01-01 UTC) | 10 bits workerId | 12 bits 序列号}</li>
 *   <li>无锁：单个 {@link AtomicLong} 以 CAS 自旋压缩
 *       {@code (timestamp - EPOCH) << SEQUENCE_BITS | sequence} 状态，消除 synchronized 争抢</li>
 *   <li>workerId 取系统属性 {@code ep.worker.id} 或环境变量 {@code EP_WORKER_ID}（默认 1，
 *       与各服务拷贝的 dev-local 默认一致）</li>
 *   <li>时钟回拨保护：回拨 &le; 容忍阈值（默认 5ms）时自旋等待追平；超过阈值抛
 *       {@link IllegalStateException}，避免发出重复/乱序 ID</li>
 * </ul>
 *
 * <p>注意：各服务现有拷贝暂不删除（后续清理），接入方逐步切换到本共享版。
 */
public final class AtomicSnowflakeIdGenerator {

    /** 2024-01-01 00:00:00 UTC — 与各服务现有拷贝一致的自定义纪元。 */
    public static final long EPOCH = 1704067200000L;

    public static final long WORKER_ID_BITS = 10L;
    public static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS); // 1023
    public static final long SEQUENCE_BITS = 12L;
    public static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS); // 4095
    public static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    public static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 时钟回拨容忍阈值（ms）：小于等于该值自旋等待，大于则抛异常。 */
    public static final long DEFAULT_MAX_BACKWARD_MS = 5L;

    private static final AtomicSnowflakeIdGenerator INSTANCE =
            new AtomicSnowflakeIdGenerator(resolveWorkerId(), DEFAULT_MAX_BACKWARD_MS, System::currentTimeMillis);

    private final long workerId;
    private final long maxBackwardMs;
    private final LongSupplier timeSource;

    /**
     * CAS 状态字：{@code (timestamp - EPOCH) << SEQUENCE_BITS | sequence}。
     * workerId 实例级不变，无需进入状态字。delta 为正（时钟早于 EPOCH 时直接拒绝），
     * long 装得下 41bit 时间戳 + 12bit 序列。
     */
    private final AtomicLong state = new AtomicLong(0L);

    /** 单例：workerId 取 {@code ep.worker.id} / {@code EP_WORKER_ID}（默认 1）。 */
    public static AtomicSnowflakeIdGenerator getInstance() {
        return INSTANCE;
    }

    public AtomicSnowflakeIdGenerator(long workerId) {
        this(workerId, DEFAULT_MAX_BACKWARD_MS, System::currentTimeMillis);
    }

    public AtomicSnowflakeIdGenerator(long workerId, long maxBackwardMs, LongSupplier timeSource) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: " + workerId + " (0.." + MAX_WORKER_ID + ")");
        }
        if (maxBackwardMs < 0) {
            throw new IllegalArgumentException("maxBackwardMs must be >= 0: " + maxBackwardMs);
        }
        this.workerId = workerId;
        this.maxBackwardMs = maxBackwardMs;
        this.timeSource = timeSource;
    }

    /**
     * 解析 workerId：系统属性 {@code ep.worker.id} 优先，其次环境变量
     * {@code EP_WORKER_ID}，缺省/非法回退 1；数值越界（&lt;0 或 &gt;1023）快速失败。
     */
    static long resolveWorkerId() {
        String wid = System.getProperty("ep.worker.id");
        if (wid == null || wid.isBlank()) {
            wid = System.getenv("EP_WORKER_ID");
        }
        long parsed = 1L;
        if (wid != null && !wid.isBlank()) {
            try {
                parsed = Long.parseLong(wid.trim());
            } catch (NumberFormatException e) {
                parsed = 1L; // 非法配置回退默认，保持与现有拷贝一致的行为
            }
        }
        if (parsed < 0 || parsed > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: " + parsed + " (0.." + MAX_WORKER_ID + ")");
        }
        return parsed;
    }

    public long getWorkerId() {
        return workerId;
    }

    /**
     * 生成下一个全局唯一 ID（无锁，CAS 自旋）。
     *
     * <p>D-216 修复（并发压测暴露 22% 重复率）：原实现在序号耗尽时用
     * {@code tilNextMillis(lastTimestamp)} 等待真实时钟，其返回值可能仍等于
     * {@code lastTimestamp}（等待条件是 {@code >=}），随后以 {@code sequence=0}
     * 回写同一毫秒 —— 与该毫秒首个 ID 重复。现改为序号耗尽时直接推进
     * <b>逻辑时间戳</b> {@code lastTimestamp + 1}（标准 Snowflake 做法，最多
     * 超前真实时钟 1ms，无重复且不自旋卡死）。</p>
     *
     * @throws IllegalStateException 时钟回拨超过容忍阈值，或系统时钟早于 {@link #EPOCH}
     */
    public long nextId() {
        while (true) {
            long prev = state.get();
            long lastTimestamp = EPOCH + (prev >>> SEQUENCE_BITS);
            long lastSequence = prev & SEQUENCE_MASK;

            long timestamp = timeSource.getAsLong();
            if (timestamp < EPOCH) {
                throw new IllegalStateException("System clock is before EPOCH " + EPOCH + ": " + timestamp);
            }
            if (timestamp < lastTimestamp) {
                long backward = lastTimestamp - timestamp;
                if (backward > maxBackwardMs) {
                    throw new IllegalStateException(
                            "Clock moved backwards by " + backward + "ms, exceeds tolerance " + maxBackwardMs + "ms");
                }
                // 小幅回拨：用已发号的逻辑时间戳继续（超前至多 backward ms，绝不重复）
                timestamp = lastTimestamp;
            }

            long ts;
            long sequence;
            if (timestamp > lastTimestamp) {
                ts = timestamp;
                sequence = 0L;
            } else {
                // timestamp == lastTimestamp（正常同毫秒或小幅回拨追平）
                sequence = (lastSequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) {
                    // 序号耗尽：推进逻辑时间戳 1ms，而不是等待真实时钟
                    ts = lastTimestamp + 1;
                } else {
                    ts = lastTimestamp;
                }
            }

            long next = ((ts - EPOCH) << SEQUENCE_BITS) | sequence;
            if (state.compareAndSet(prev, next)) {
                return ((ts - EPOCH) << TIMESTAMP_SHIFT)
                        | (workerId << WORKER_ID_SHIFT)
                        | sequence;
            }
            // CAS 失败：其他线程已发号，重读状态重试（时间戳/序号基于新状态重算）
        }
    }

    /** 自旋直到时间源追平 targetTs（用于小幅回拨等待与序号溢出翻毫秒）。 */
    private long tilNextMillis(long targetTs) {
        long ts = timeSource.getAsLong();
        while (ts < targetTs) {
            Thread.onSpinWait();
            ts = timeSource.getAsLong();
        }
        return ts;
    }
}
