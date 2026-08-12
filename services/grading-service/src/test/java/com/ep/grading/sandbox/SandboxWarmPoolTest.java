package com.ep.grading.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-185: SandboxWarmPool acquire/release/minReady tests.
 */
class SandboxWarmPoolTest {

    @Test
    void acquire_and_release_returns_slot() throws Exception {
        SandboxWarmPool pool = new SandboxWarmPool(3);
        assertEquals(3, pool.availablePermits("PYTHON"));
        LanguageSpec spec = pool.acquire("python");
        assertNotNull(spec);
        assertEquals(LanguageSpec.PYTHON, spec);
        assertEquals(2, pool.availablePermits("PYTHON"));
        pool.release("PYTHON");
        assertEquals(3, pool.availablePermits("PYTHON"));
    }

    @Test
    void min_ready_matches_default() {
        SandboxWarmPool pool = new SandboxWarmPool(5);
        assertEquals(5, pool.minReady("JAVA"));
        assertEquals(5, pool.minReady("GO"));
        assertEquals(5, pool.minReady("CPP"));
    }

    @Test
    void try_acquire_returns_null_when_exhausted() {
        SandboxWarmPool pool = new SandboxWarmPool(1);
        assertNotNull(pool.tryAcquire("JAVA"));
        assertEquals(0, pool.availablePermits("JAVA"));
        // exhausted — should return null, not block
        assertEquals(null, pool.tryAcquire("JAVA"));
    }

    @Test
    void acquire_unknown_language_throws() {
        SandboxWarmPool pool = new SandboxWarmPool();
        assertThrows(IllegalArgumentException.class, () -> pool.acquire("rust"));
    }

    @Test
    void each_language_has_independent_pool() throws Exception {
        SandboxWarmPool pool = new SandboxWarmPool(2);
        pool.acquire("JAVA");
        assertEquals(1, pool.availablePermits("JAVA"));
        // PYTHON pool unaffected
        assertEquals(2, pool.availablePermits("PYTHON"));
    }

    @Test
    void release_restores_permit_count() {
        SandboxWarmPool pool = new SandboxWarmPool(2);
        pool.tryAcquire("GO");
        assertEquals(1, pool.availablePermits("GO"));
        pool.release("GO");
        assertEquals(2, pool.availablePermits("GO"));
        // releasing more than acquired is tolerated (semaphore permits grow)
        pool.release("GO");
        assertTrue(pool.availablePermits("GO") >= 2);
    }
}
