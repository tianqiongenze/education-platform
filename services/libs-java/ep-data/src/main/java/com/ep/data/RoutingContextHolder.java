package com.ep.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-216: Explicit, scope-bounded routing override. Takes precedence over the
 * transactional readOnly flag, letting a read-only transaction still hit the
 * write pool (read-your-own-writes after a commit on a lagging replica) or a
 * write transaction be pinned for audit reasons.
 *
 * Always use try-with-resources — the holder is ThreadLocal-based:
 * <pre>
 * try (var ignored = RoutingContextHolder.forceWrite()) {
 *     repo.findForUpdate(id);   // routed to the write pool
 * }
 * </pre>
 *
 * <p><b>Threading semantics (deliberate):</b> a plain ThreadLocal, not
 * InheritableThreadLocal — the override describes a lexical scope on the
 * current call stack, and an inherited copy would outlive the scope's close()
 * on pooled @Async threads (stale-override hazard). For cross-thread work,
 * annotate the target method with {@code @ReadOnly}/{@code @WriteRoute}
 * instead of relying on the caller's scope.</p>
 */
public final class RoutingContextHolder {

    private static final Logger log = LoggerFactory.getLogger(RoutingContextHolder.class);

    public enum Route { WRITE, READ }

    private static final ThreadLocal<Route> OVERRIDE = new ThreadLocal<>();

    private RoutingContextHolder() {
    }

    public static Scope forceWrite() {
        return push(Route.WRITE);
    }

    public static Scope forceRead() {
        return push(Route.READ);
    }

    /** Current explicit override, or null when routing follows the tx flag. */
    public static Route currentOverride() {
        return OVERRIDE.get();
    }

    private static Scope push(Route route) {
        Route previous = OVERRIDE.get();
        OVERRIDE.set(route);
        log.trace("routing override {} -> {}", previous, route);
        return new Scope(previous);
    }

    /** Restores the previous override (or clears it) on close. Nested-safe. */
    public static final class Scope implements AutoCloseable {
        private final Route previous;

        private Scope(Route previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
