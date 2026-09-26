package com.ep.web.common.config;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 连接获取信号量封装。
 *
 * <p>背景：项目已启用 Spring 虚拟线程（spring.threads.virtual.enabled=true）。
 * 虚拟线程数量可远超平台线程，但底层 HikariCP 连接池容量有限（通常 10-50 个连接）。
 * 若大量虚拟线程同时争抢 DB 连接，会导致：
 * <ol>
 *   <li>HikariCP 连接获取超时（ConnectionTimeout）</li>
 *   <li>虚拟线程在等待连接时 pinning 到平台线程，削弱虚拟线程收益</li>
 *   <li>级联超时引发系统雪崩</li>
 * </ol>
 *
 * <p>本封装在业务线程真正去获取 HikariCP 连接前，先用 {@link Semaphore} 限流：
 * 只有拿到 permit 的线程才会进入连接池竞争，从而把同时争抢连接的并发数
 * 控制在 HikariCP maximumPoolSize 量级，避免连接池被打穿。</p>
 *
 * <p>典型用法：通过 {@link DbConnectionTemplate#executeWithPermit} 包裹实际的
 * 数据库操作，或在自定义数据访问入口处手动调用 {@link #tryAcquire} /
 * {@link #release()}（务必 try-finally 释放）。</p>
 *
 * <p>线程安全：基于 {@link Semaphore}，本身线程安全。</p>
 */
public class DbConnectionSemaphore {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionSemaphore.class);

    private final Semaphore semaphore;

    /**
     * 构造一个 fair 信号量。使用公平模式避免饥饿：在虚拟线程高并发场景下，
     * 公平调度能保证先排队的虚拟线程先拿到 permit，避免个别请求长时间被饿死。
     *
     * @param permits 许可总数，通常等于 HikariCP maximumPoolSize
     */
    public DbConnectionSemaphore(int permits) {
        // fair=true：FIFO 公平调度，防饥饿
        this.semaphore = new Semaphore(permits, true);
        log.info("DbConnectionSemaphore 初始化完成，permits={}（公平模式）", permits);
    }

    /**
     * 带超时地获取一个许可。获取成功后，调用方必须在使用完 DB 连接后
     * 调用 {@link #release()} 归还许可，建议放在 finally 块中。
     *
     * @param timeout 超时时长
     * @param unit    时间单位
     * @return true 表示在超时前获取到许可；false 表示超时未获取到
     * @throws InterruptedException 如果等待期间线程被中断
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        boolean acquired = semaphore.tryAcquire(timeout, unit);
        if (acquired) {
            log.debug("DB 连接许可获取成功，剩余可用 permits={}", semaphore.availablePermits());
        } else {
            // 超时未获取：意味着当前已经有 permits 个虚拟线程在持连接，
            // 调用方应直接返回降级响应而非继续阻塞，防止雪崩
            log.warn("DB 连接许可获取超时（{} {}），当前可用 permits={}，触发限流降级",
                    timeout, unit, semaphore.availablePermits());
        }
        return acquired;
    }

    /**
     * 释放一个许可。必须与 {@link #tryAcquire} 配对调用，建议置于 finally 块。
     */
    public void release() {
        semaphore.release();
        log.debug("DB 连接许可已释放，当前可用 permits={}", semaphore.availablePermits());
    }

    /**
     * @return 当前可用的许可数（仅用于监控/测试，不应用于业务决策）
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * @return 信号量配置的总许可数
     */
    public int totalPermits() {
        return semaphore.getQueueLength() + semaphore.availablePermits();
    }
}
