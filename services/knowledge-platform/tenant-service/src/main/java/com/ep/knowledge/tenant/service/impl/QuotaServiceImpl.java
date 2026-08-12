package com.ep.knowledge.tenant.service.impl;

import com.ep.knowledge.tenant.service.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis-based token bucket rate limiter.
 * Lua script atomically decrements tokens.
 */
@Service
public class QuotaServiceImpl implements QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceImpl.class);
    private static final String KEY_PREFIX = "quota:";

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT;
    static {
        ACQUIRE_SCRIPT = new DefaultRedisScript<>();
        ACQUIRE_SCRIPT.setScriptText(
                "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') " +
                "if cur >= tonumber(ARGV[1]) then " +
                "  redis.call('DECRBY', KEYS[1], ARGV[1]) " +
                "  return 1 " +
                "else " +
                "  return 0 " +
                "end"
        );
        ACQUIRE_SCRIPT.setResultType(Long.class);
    }

    private final RedisTemplate<String, Object> redisTemplate;

    public QuotaServiceImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String tenantId, int tokens) {
        String key = KEY_PREFIX + tenantId;
        Long allowed = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(key), String.valueOf(tokens));
        boolean ok = allowed != null && allowed == 1L;
        if (!ok) {
            log.debug("Rate-limited tenant={} tokens={}", tenantId, tokens);
        }
        return ok;
    }

    @Override
    public long remaining(String tenantId) {
        Object val = redisTemplate.opsForValue().get(KEY_PREFIX + tenantId);
        if (val == null) return 0L;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
