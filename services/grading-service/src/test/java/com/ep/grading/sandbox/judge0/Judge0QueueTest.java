package com.ep.grading.sandbox.judge0;

import com.ep.grading.sandbox.ExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-185: Judge0Queue tests — mock {@link StringRedisTemplate} to verify
 * enqueue/dequeue/storeResult/loadResult behaviour without a live Redis.
 */
@SuppressWarnings("unchecked")
class Judge0QueueTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private ValueOperations<String, String> valueOps;
    private ObjectProvider<StringRedisTemplate> redisProvider;
    private Judge0Queue queue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        valueOps = mock(ValueOperations.class);
        redisProvider = mock(ObjectProvider.class);

        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        queue = new Judge0Queue(redisProvider, new ObjectMapper());
    }

    @Test
    void enqueue_lpushes_json_task() throws Exception {
        Judge0Task task = new Judge0Task("tok-1", "PYTHON", "print('hi')", "", 5);
        when(listOps.leftPush(eq(Judge0Queue.QUEUE_KEY), anyString())).thenReturn(1L);

        boolean ok = queue.enqueue(task);

        assertTrue(ok);
        verify(listOps).leftPush(eq(Judge0Queue.QUEUE_KEY), anyString());
    }

    @Test
    void enqueue_returns_false_when_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        boolean ok = queue.enqueue(
                new Judge0Task("tok-2", "JAVA", "x", "", 5));
        assertFalse(ok);
        verify(listOps, never()).leftPush(anyString(), anyString());
    }

    @Test
    void deblock_returns_task_when_json_present() throws Exception {
        Judge0Task task = new Judge0Task("tok-3", "GO", "package main", "", 10);
        String json = new ObjectMapper().writeValueAsString(task);
        when(listOps.rightPop(eq(Judge0Queue.QUEUE_KEY), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(json);

        Optional<Judge0Task> result = queue.deblock(5L);

        assertTrue(result.isPresent());
        assertEquals("tok-3", result.get().token());
        assertEquals("GO", result.get().language());
    }

    @Test
    void deblock_returns_empty_on_timeout() {
        when(listOps.rightPop(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        Optional<Judge0Task> result = queue.deblock(1L);

        assertTrue(result.isEmpty());
    }

    @Test
    void deblock_returns_empty_when_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        Optional<Judge0Task> result = queue.deblock(5L);
        assertTrue(result.isEmpty());
    }

    @Test
    void store_result_sets_value_with_ttl() {
        Judge0Result result = new Judge0Result("tok-4",
                new ExecutionResult(0, "ok", "", 12L, false));
        doNothing().when(valueOps)
                .set(anyString(), anyString(), eq(Judge0Queue.RESULT_TTL));

        boolean ok = queue.storeResult("tok-4", result);

        assertTrue(ok);
        verify(valueOps).set(
                eq(Judge0Queue.RESULT_KEY_PREFIX + "tok-4"),
                anyString(),
                eq(Judge0Queue.RESULT_TTL));
    }

    @Test
    void store_result_returns_false_when_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        boolean ok = queue.storeResult("x", new Judge0Result("x",
                new ExecutionResult(0, "", "", 0L, false)));
        assertFalse(ok);
    }

    @Test
    void load_result_returns_present_when_key_exists() throws Exception {
        Judge0Result result = new Judge0Result("tok-5",
                new ExecutionResult(0, "hello", "", 3L, false));
        String json = new ObjectMapper().writeValueAsString(result);
        when(valueOps.get(Judge0Queue.RESULT_KEY_PREFIX + "tok-5"))
                .thenReturn(json);

        Optional<Judge0Result> loaded = queue.loadResult("tok-5");

        assertTrue(loaded.isPresent());
        assertEquals("tok-5", loaded.get().token());
        assertEquals(0, loaded.get().result().exitCode());
    }

    @Test
    void load_result_returns_empty_when_key_missing() {
        when(valueOps.get(anyString())).thenReturn(null);
        Optional<Judge0Result> loaded = queue.loadResult("missing");
        assertTrue(loaded.isEmpty());
    }

    @Test
    void load_result_returns_empty_when_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        assertTrue(queue.loadResult("x").isEmpty());
    }

    @Test
    void store_then_load_roundtrip_preserves_result() {
        doNothing().when(valueOps)
                .set(anyString(), anyString(), eq(Judge0Queue.RESULT_TTL));
        Judge0Result original = new Judge0Result("tok-rt",
                new ExecutionResult(1, "out", "err", 999L, true));
        assertTrue(queue.storeResult("tok-rt", original));

        // simulate Redis holding the value we just wrote
        when(valueOps.get(Judge0Queue.RESULT_KEY_PREFIX + "tok-rt"))
                .thenAnswer(inv -> {
                    try {
                        return new ObjectMapper().writeValueAsString(original);
                    } catch (Exception e) {
                        return null;
                    }
                });

        Optional<Judge0Result> loaded = queue.loadResult("tok-rt");
        assertTrue(loaded.isPresent());
        ExecutionResult r = loaded.get().result();
        assertEquals(1, r.exitCode());
        assertEquals("out", r.stdout());
        assertEquals("err", r.stderr());
        assertEquals(999L, r.executionTimeMs());
        assertTrue(r.timedOut());
    }
}
