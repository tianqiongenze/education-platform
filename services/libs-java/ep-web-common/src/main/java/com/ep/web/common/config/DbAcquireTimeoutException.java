package com.ep.web.common.config;

/**
 * DB 连接许可获取超时异常。
 *
 * <p>当 {@link DbConnectionTemplate#executeWithPermit} 在限定时间内未能拿到
 * DB 连接许可（即当前已有 permits 个虚拟线程在持连接竞争 HikariCP）时抛出。
 * 调用方可捕获此异常返回降级响应（HTTP 429 / 503），避免级联雪崩。</p>
 *
 * <p>继承 {@link RuntimeException} 以便在 lambda / Callable 中直接抛出。</p>
 */
public class DbAcquireTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DbAcquireTimeoutException(String message) {
        super(message);
    }

    public DbAcquireTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
