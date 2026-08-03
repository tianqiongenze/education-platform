package com.ep.rw;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType t = RoutingHolder.get();
        return t == null ? DataSourceType.WRITE : t;
    }
}
