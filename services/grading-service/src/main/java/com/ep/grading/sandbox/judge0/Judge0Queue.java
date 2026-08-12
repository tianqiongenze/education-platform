package com.ep.grading.sandbox.judge0;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * D-185: Redis-backed queue for Judge0-style asynchronous code execution tasks.
 * <p>
 * Uses a plain Redis list keyed {@code judge0:queue}:
 * <ul>
 *   <li>{@link #enqueue(Judge0Task)} — {@code LPUSH} the JSON-serialised task</li>
 *   <li>{@link #deblock(long)} — blocking {@code BRPOP} with a timeout</li>
 * </ul>
 * The {@link StringRedisTemplate} is injected via {@link ObjectProvider} so unit
 * tests can run without a Redis connection (the methods degrade to no-ops and
 * return empty optionals).
 */
@Component
public class Judge0Queue {

    private static final Logger log = LoggerFactory.getLogger(Judge0Queue.class);

    /** Redis list key holding pending tasks. */
    public static final String QUEUE_KEY = "judge0:queue";

    /** Redis key prefix for per-task results. */
    public static final String RESULT_KEY_PREFIX = "judge0:result:";

    /** Result TTL — 1 hour. */
    public static final Duration RESULT_TTL = Duration.ofHours(1);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    public Judge0Queue(ObjectProvider<StringRedisTemplate> redisProvider,
                       ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue a task (LPUSH). Returns {@code false} if Redis is unavailable.
     */
    public boolean enqueue(Judge0Task task) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn("judge0: redis unavailable, cannot enqueue task {}", task.token());
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(task);
            redis.opsForList().leftPush(QUEUE_KEY, json);
            log.debug("judge0: enqueued task {}", task.token());
            return true;
        } catch (Exception e) {
            log.error("judge0: enqueue failed for task {}", task.token(), e);
            return false;
        }
    }

    /**
     * Blocking dequeue (BRPOP). Returns the task or {@code Optional#empty()}
     * on timeout / Redis unavailable.
     *
     * @param timeoutSec maximum seconds to block waiting for a task
     */
    public Optional<Judge0Task> deblock(long timeoutSec) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForList().rightPop(QUEUE_KEY, timeoutSec, TimeUnit.SECONDS);
            if (json == null) {
                return Optional.empty();
            }
            Judge0Task task = objectMapper.readValue(json, Judge0Task.class);
            return Optional.of(task);
        } catch (Exception e) {
            log.error("judge0: dequeue failed", e);
            return Optional.empty();
        }
    }

    /**
     * Store the execution result for a token in Redis with a 1h TTL so clients
     * can poll {@code judge0:result:{token}}.
     */
    public boolean storeResult(String token, Judge0Result result) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(RESULT_KEY_PREFIX + token, json, RESULT_TTL);
            return true;
        } catch (Exception e) {
            log.error("judge0: storeResult failed for token {}", token, e);
            return false;
        }
    }

    /** Load a previously stored result (or empty if missing/expired). */
    public Optional<Judge0Result> loadResult(String token) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(RESULT_KEY_PREFIX + token);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, Judge0Result.class));
        } catch (Exception e) {
            log.error("judge0: loadResult failed for token {}", token, e);
            return Optional.empty();
        }
    }
}
