package com.ep.data;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * D-216: Read/write-split routing datasource (industry-standard pattern,
 * cf. Vlad Mihalcea / Baeldung transactional routing), hardened for this
 * platform:
 *
 * <ol>
 *   <li>{@link RoutingContextHolder} explicit override wins (read-your-own-writes
 *       after writes on lagging replicas, forced pinning);</li>
 *   <li>else a read-only Spring transaction routes READ
 *       ({@code TransactionSynchronizationManager#isCurrentTransactionReadOnly()});</li>
 *   <li>else WRITE.</li>
 * </ol>
 *
 * The auto-configuration wraps this in a {@code LazyConnectionDataSourceProxy}
 * so the routing decision is deferred until the first statement — the common
 * pitfall of eagerly grabbing the connection before the tx flag is set.
 *
 * Providers without read replicas (H2) resolve READ to the write pool —
 * same bean graph, no special-casing at call sites.
 */
public class EpRoutingDataSource extends AbstractRoutingDataSource {

    public static final String WRITE_KEY = "write";
    public static final String READ_KEY = "read";

    @Override
    protected Object determineCurrentLookupKey() {
        RoutingContextHolder.Route override = RoutingContextHolder.currentOverride();
        if (override != null) {
            return override == RoutingContextHolder.Route.WRITE ? WRITE_KEY : READ_KEY;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return READ_KEY;
        }
        return WRITE_KEY;
    }
}
