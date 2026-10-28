package com.ep.integration.bdd;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry mapping step text (keyword-stripped, exactly as parsed by
 * {@link GherkinParser}) to a step implementation lambda. Intentionally
 * exact-text matching only: no regex, no glue-code magic — a missing mapping
 * is reported verbatim by the runner so failures are immediately readable.
 */
public final class StepRegistry {

    /** A single step implementation; may throw anything (reported by the runner). */
    @FunctionalInterface
    public interface Step {
        void run() throws Throwable;
    }

    private final Map<String, Step> steps = new LinkedHashMap<>();

    /** Registers {@code text -> impl}. Re-registering the same text overwrites (last wins). */
    public void register(String text, Step impl) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(impl, "impl");
        steps.put(text, impl);
    }

    /** Looks up the implementation for a parsed step text; {@code null} when unmapped. */
    public Step lookup(String text) {
        return steps.get(text);
    }

    public int size() {
        return steps.size();
    }
}
