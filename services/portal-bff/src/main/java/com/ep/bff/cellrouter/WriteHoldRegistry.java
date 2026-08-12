package com.ep.bff.cellrouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 写挂起名单 — failover 期间阻止受影响用户的写请求, 保证数据一致性.
 *
 * <p>D-006 (Iron Law 1): 会话状态外部化到 Redis. 生产路径通过 Redis key (带 TTL) 共享挂起状态,
 * 使得多 cell 部署下任一 cell 都能正确识别写挂起用户.
 * key 模式: {@code route:hold:{userId}}, TTL 来自 {@code writeHoldTtlSeconds} (默认 1800s=30min).
 *
 * <p>EP_DEV_ONLY (Iron Law 0): dev/CI 用本地内存; 当 RedisTemplate 不可用 (如纯单元测试) 也回退到本地内存.
 * D-039: ConcurrentHashMap + Redis 原子操作, 无 synchronized.
 *
 * <p>条目带 TTL, 超过 {@code writeHoldTtlSeconds} 自动驱逐. failover 完成后调用
 * {@link #release(String)} 显式解除.
 */
@Component
public class WriteHoldRegistry {

    private static final Logger log = LoggerFactory.getLogger(WriteHoldRegistry.class);

    /** Redis key 前缀: {@code route:hold:{userId}}. */
    private static final String KEY_PREFIX = "route:hold:";

    /** SCAN 模式: 匹配所有挂起用户. */
    private static final String SCAN_PATTERN = "route:hold:*";

    @Value("${EP_DEV_ONLY:false}")
    private String epDevOnly;

    @Value("${ep.cell-router.write-hold-ttl:1800}")
    private long writeHoldTtlSeconds;

    /** 注入可空的 StringRedisTemplate (dev/CI/纯单元测试无 Redis 时为 null). */
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    /** userId → 挂起时间戳 (epoch millis). 仅 dev/CI/Redis 不可用时使用本地存储. */
    private final ConcurrentHashMap<String, Long> holdMap = new ConcurrentHashMap<>();

    public WriteHoldRegistry(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * 检查用户是否处于写挂起状态.
     *
     * <p>本地模式副作用: 若条目已过期则惰性驱逐. Redis 模式下 TTL 由 Redis 自动管理.
     *
     * @param userId 用户 ID
     * @return true 表示处于挂起状态
     */
    public boolean isHeld(String userId) {
        if (userId == null || userId.isEmpty()) {
            return false;
        }
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            Long heldAt = holdMap.get(userId);
            if (heldAt == null) {
                return false;
            }
            if (isExpired(heldAt)) {
                // 惰性驱逐过期条目
                holdMap.remove(userId, heldAt);
                return false;
            }
            return true;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
    }

    /**
     * 将用户加入写挂起名单.
     *
     * @param userId 用户 ID
     */
    public void hold(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            holdMap.put(userId, System.currentTimeMillis());
            log.info("WriteHoldRegistry: user {} held (TTL={}s, in-memory)", userId, writeHoldTtlSeconds);
            return;
        }
        redisTemplate.opsForValue().set(
            KEY_PREFIX + userId,
            String.valueOf(System.currentTimeMillis()),
            writeHoldTtlSeconds,
            TimeUnit.SECONDS);
        log.info("WriteHoldRegistry: user {} held (TTL={}s, redis)", userId, writeHoldTtlSeconds);
    }

    /**
     * 解除用户写挂起.
     *
     * @param userId 用户 ID
     */
    public void release(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            holdMap.remove(userId);
            log.info("WriteHoldRegistry: user {} released", userId);
            return;
        }
        redisTemplate.delete(KEY_PREFIX + userId);
        log.info("WriteHoldRegistry: user {} released", userId);
    }

    /**
     * 获取当前挂起的用户集合 (供监控/测试).
     *
     * <p>本地模式: 驱逐过期条目并返回快照. Redis 模式: 通过 SCAN 遍历 {@code route:hold:*} (O(N), 监控可接受).
     *
     * @return 不可变快照集合
     */
    public Set<String> getHeldUsers() {
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            long now = System.currentTimeMillis();
            long ttlMillis = writeHoldTtlSeconds * 1000L;
            Set<String> result = new HashSet<>();
            for (Map.Entry<String, Long> e : holdMap.entrySet()) {
                if (now - e.getValue() < ttlMillis) {
                    result.add(e.getKey());
                }
            }
            return Set.copyOf(result);
        }
        Set<String> result = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(SCAN_PATTERN).count(100).build();
        int prefixLen = KEY_PREFIX.length();
        // SCAN 游标遍历, 避免阻塞 Redis (KEYS 命令在生产不可用).
        // 使用 executeWithStickyConnection 确保 Cursor 跨多次 Redis 调用时连接保持打开.
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next());
                    if (key.startsWith(KEY_PREFIX)) {
                        result.add(key.substring(prefixLen));
                    }
                }
            }
            return null;
        });
        log.debug("WriteHoldRegistry: getHeldUsers returned {} users via SCAN", result.size());
        return Set.copyOf(result);
    }

    /**
     * 清除所有挂起 (测试用).
     *
     * <p>Redis 模式下不支持按前缀批量删除 (per-user key), 仅 dev/CI 本地模式可用.
     */
    public void clearAll() {
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (useInMemory(redisTemplate)) {
            holdMap.clear();
            return;
        }
        log.warn("WriteHoldRegistry: clearAll not supported in Redis mode (per-user keys route:hold:*)");
    }

    /**
     * 设置 TTL (测试用, 允许覆盖默认值).
     *
     * @param ttlSeconds TTL 秒数
     */
    public void setWriteHoldTtlSeconds(long ttlSeconds) {
        this.writeHoldTtlSeconds = ttlSeconds;
    }

    /**
     * 测试专用: 以指定时间戳挂起用户 (用于 TTL 测试). 仅本地内存路径, 不写 Redis.
     *
     * @param userId     用户 ID
     * @param heldAtMillis 挂起时间戳 (epoch millis)
     */
    void holdAt(String userId, long heldAtMillis) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        holdMap.put(userId, heldAtMillis);
    }

    private boolean isExpired(Long heldAt) {
        long ageMillis = System.currentTimeMillis() - heldAt;
        return ageMillis >= writeHoldTtlSeconds * 1000L;
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
            log.debug("WriteHoldRegistry: StringRedisTemplate unavailable, using in-memory fallback");
            return true;
        }
        return false;
    }

    private boolean isDevOnly() {
        return "true".equalsIgnoreCase(epDevOnly) || "1".equals(epDevOnly);
    }
}
