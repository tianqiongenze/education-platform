package com.ep.bff.cellrouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 路由纠正计数器 — 记录用户 misroute 累计次数, 防止无限重路由循环.
 *
 * <p>D-006 (Iron Law 1): 会话状态外部化到 Redis. 生产路径通过 Redis INCR 共享计数,
 * 使得多 cell 部署下用户在 cell 之间跳转时计数不会丢失 (D-083 "≤3 重定向 / >3 拒绝" 逻辑依赖于此).
 * key 模式: {@code route:correct:{userId}}, TTL 1 小时.
 *
 * <p>EP_DEV_ONLY (Iron Law 0): dev/CI 用本地内存; 当 RedisTemplate 不可用 (如纯单元测试) 也回退到本地内存.
 * D-039: ConcurrentHashMap + AtomicInteger + Redis 原子操作, 无 synchronized.
 *
 * <p>当用户到达正确 cell 时, 调用 {@link #clear(String)} 重置计数.
 */
@Component
public class RouteContext {

    private static final Logger log = LoggerFactory.getLogger(RouteContext.class);

    /** Redis key 前缀: {@code route:correct:{userId}}. */
    private static final String KEY_PREFIX = "route:correct:";

    /** Redis TTL: 计数器保留 1 小时. */
    private static final long TTL_HOURS = 1L;

    @Value("${EP_DEV_ONLY:false}")
    private String epDevOnly;

    /** 注入可空的 StringRedisTemplate (dev/CI/纯单元测试无 Redis 时为 null). */
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    /** userId → 纠正次数. 仅 dev/CI/Redis 不可用时使用本地存储. */
    private final ConcurrentHashMap<String, AtomicInteger> counterMap = new ConcurrentHashMap<>();

    public RouteContext(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * 递增用户的纠正计数, 返回递增后的值.
     *
     * @param userId 用户 ID
     * @return 递增后的计数
     */
    public int incrementCorrectCount(String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            return counterMap
                .computeIfAbsent(userId, k -> new AtomicInteger(0))
                .incrementAndGet();
        }
        String key = KEY_PREFIX + userId;
        Long after = redisTemplate.opsForValue().increment(key);
        // 每次 INCR 后刷新 TTL, 避免长期活跃用户的计数器意外过期
        redisTemplate.expire(key, TTL_HOURS, java.util.concurrent.TimeUnit.HOURS);
        return after == null ? 0 : after.intValue();
    }

    /**
     * 获取用户当前纠正计数.
     *
     * @param userId 用户 ID
     * @return 当前计数, 不存在返回 0
     */
    public int getCorrectCount(String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            AtomicInteger cnt = counterMap.get(userId);
            return cnt == null ? 0 : cnt.get();
        }
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("RouteContext: non-numeric counter value for user {}: {}", userId, value);
            return 0;
        }
    }

    /**
     * 清除用户纠正计数 (到达正确 cell 时调用).
     *
     * @param userId 用户 ID
     */
    public void clear(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            counterMap.remove(userId);
            return;
        }
        redisTemplate.delete(KEY_PREFIX + userId);
    }

    /**
     * 清除所有计数 (测试/重置用).
     *
     * <p>Redis 模式下不支持按前缀批量删除 (per-user key), 仅 dev/CI 本地模式可用.
     */
    public void clearAll() {
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            counterMap.clear();
            return;
        }
        log.warn("RouteContext: clearAll not supported in Redis mode (per-user keys route:correct:*)");
    }

    private StringRedisTemplate getRedisTemplate() {
        return redisTemplateProvider.getIfAvailable();
    }

    /**
     * 是否使用本地内存路径: EP_DEV_ONLY 或 RedisTemplate 不可用.
     */
    private boolean useInMemory(StringRedisTemplate redisTemplate) {
        if (isDevOnly()) {
            return true;
        }
        if (redisTemplate == null) {
            log.debug("RouteContext: StringRedisTemplate unavailable, using in-memory fallback");
            return true;
        }
        return false;
    }

    private boolean isDevOnly() {
        return "true".equalsIgnoreCase(epDevOnly) || "1".equals(epDevOnly);
    }
}
