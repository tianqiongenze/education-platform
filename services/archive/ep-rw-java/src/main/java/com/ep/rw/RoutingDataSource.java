package com.ep.rw;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * @deprecated D-094 (2026-08-08): ep-rw-java has 0 consumers across the
 * monorepo. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-rw-java/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType t = RoutingHolder.get();
        return t == null ? DataSourceType.WRITE : t;
    }
}
