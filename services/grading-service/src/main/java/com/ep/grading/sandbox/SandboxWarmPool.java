package com.ep.grading.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * D-185: Warm pool of language slots for the sandbox executor.
 * <p>
 * Maintains a per-language semaphore that caps concurrent executions. The
 * "warm" semantics here mirror the K8s SandboxWarmPool CRD intent: a declared
 * number of ready slots ({@code minReady}) per language that callers acquire
 * before running code and release after. In the ProcessBuilder backend the
 * slots are logical permits; a future Firecracker backend would hold actual
 * pre-booted microVMs in the same shape.
 * <p>
 * Iron Law 1 (session state externalisation) does not apply — this is
 * ephemeral in-process concurrency state, not durable session state. The pool
 * is reset on restart and recreated from the CRD defaults.
 */
@Component
public class SandboxWarmPool {

    private static final Logger log = LoggerFactory.getLogger(SandboxWarmPool.class);

    /** Default minimum ready slots per language (matches the CRD default). */
    public static final int DEFAULT_MIN_READY = 5;

    private final Map<LanguageSpec, Semaphore> permits;
    private final Map<LanguageSpec, Integer> minReady;
    private final int defaultMinReady;

    public SandboxWarmPool() {
        this(DEFAULT_MIN_READY);
    }

    public SandboxWarmPool(int defaultMinReady) {
        this.defaultMinReady = Math.max(1, defaultMinReady);
        this.permits = new EnumMap<>(LanguageSpec.class);
        this.minReady = new EnumMap<>(LanguageSpec.class);
        for (LanguageSpec spec : LanguageSpec.values()) {
            permits.put(spec, new Semaphore(this.defaultMinReady));
            minReady.put(spec, this.defaultMinReady);
        }
        log.info("D-185: SandboxWarmPool initialised (defaultMinReady={})", this.defaultMinReady);
    }

    /**
     * Acquire an execution slot for the given language, blocking until one is
     * available.
     *
     * @param language the language to acquire a slot for
     * @return the resolved {@link LanguageSpec}
     * @throws InterruptedException if the calling thread is interrupted
     */
    public LanguageSpec acquire(String language) throws InterruptedException {
        LanguageSpec spec = LanguageSpec.fromName(language);
        permits.get(spec).acquire();
        log.debug("acquired slot for {} (available={})", spec.displayName(),
                permits.get(spec).availablePermits());
        return spec;
    }

    /**
     * Try to acquire a slot without blocking.
     *
     * @return the resolved spec, or {@code null} if no slot was available
     */
    public LanguageSpec tryAcquire(String language) {
        LanguageSpec spec = LanguageSpec.fromName(language);
        if (permits.get(spec).tryAcquire()) {
            return spec;
        }
        return null;
    }

    /** Release a previously acquired slot back to the pool. */
    public void release(String language) {
        LanguageSpec spec = LanguageSpec.fromName(language);
        permits.get(spec).release();
        log.debug("released slot for {} (available={})", spec.displayName(),
                permits.get(spec).availablePermits());
    }

    /** Number of available (free) slots for the given language. */
    public int availablePermits(String language) {
        return permits.get(LanguageSpec.fromName(language)).availablePermits();
    }

    /** Configured minimum-ready (total permits) for the given language. */
    public int minReady(String language) {
        return minReady.get(LanguageSpec.fromName(language));
    }
}
