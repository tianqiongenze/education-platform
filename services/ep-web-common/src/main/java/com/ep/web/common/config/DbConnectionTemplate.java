package com.ep.web.common.config;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 连接许可执行模板。
 *
 * <p>提供一种「获取许可 → 执行 DB 操作 → 释放许可」的标准范式，避免
 * 业务代码直接操作 {@link DbConnectionSemaphore} 时忘记在 finally 中
 * release 导致许可泄漏。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * @Autowired
 * private DbConnectionTemplate dbConnectionTemplate;
 *
 * public Order findById(Long id) {
 *     return dbConnectionTemplate.executeWithPermit(() -> orderMapper.selectById(id));
 * }
 * }</pre>
 *
 * <p>当许可获取超时（限流触发）时，{@link #executeWithPermit} 会抛出
 * {@link DbAcquireTimeoutException}，调用方可据此返回降级响应。</p>
 */
public class DbConnectionTemplate {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionTemplate.class);

    private final DbConnectionSemaphore semaphore;

    /** 获取许可的默认超时时间（毫秒）。可通过构造参数覆盖。 */
    private final long timeoutMs;

    /**
     * @param semaphore  DB 连接信号量
     * @param timeoutMs  获取许可的超时时间（毫秒）
     */
    public DbConnectionTemplate(DbConnectionSemaphore semaphore, long timeoutMs) {
        this.semaphore = semaphore;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 在持有 DB 连接许可的前提下执行任务。
     *
     * <p>语义：先 {@link DbConnectionSemaphore#tryAcquire} 拿到 permit，
     * 再执行 {@code task}，无论任务成功或抛异常都在 finally 中 release。
     * 若 permit 获取超时，直接抛 {@link DbAcquireTimeoutException}，
     * 不执行 task。</p>
     *
     * @param task 需要访问数据库的任务
     * @param <T>  返回类型
     * @return 任务执行结果
     * @throws DbAcquireTimeoutException 许可获取超时（限流降级）
     * @throws RuntimeException          任务本身抛出的异常将被原样抛出
     */
    public <T> T executeWithPermit(Callable<T> task) {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // 限流触发：放弃执行，向上抛出可识别异常，由 GlobalExceptionHandler 兜底降级
                log.warn("DB 访问被限流，当前可用 permits={}，超时 {}ms，放弃执行任务",
                        semaphore.availablePermits(), timeoutMs);
                throw new DbAcquireTimeoutException(
                        "DB 连接许可获取超时，触发限流降级（timeout=" + timeoutMs + "ms）");
            }
            return task.call();
        } catch (DbAcquireTimeoutException e) {
            throw e; // 已包装，直接上抛
        } catch (RuntimeException e) {
            throw e; // 业务运行时异常原样上抛
        } catch (Exception e) {
            // Callable.call() 抛出的受检异常包装为运行时异常
            throw new RuntimeException("DB 任务执行失败", e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    /**
     * 不带返回值的执行版本，便于包装 void 操作。
     */
    public void executeWithPermit(Runnable task) {
        executeWithPermit(() -> {
            task.run();
            return null;
        });
    }
}
