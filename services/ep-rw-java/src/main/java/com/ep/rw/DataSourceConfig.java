package com.ep.rw;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "ep.rw.enabled", matchIfMissing = true)
public class DataSourceConfig {
    @Bean @ConfigurationProperties("spring.datasource.write")
    public DataSource writeDataSource() { return new HikariDataSource(); }
    @Bean @ConfigurationProperties("spring.datasource.read")
    public DataSource readDataSource() { return new HikariDataSource(); }

    @Bean
    public AbstractRoutingDataSource routingDataSource(
            @Qualifier("writeDataSource") DataSource write,
            @Qualifier("readDataSource") DataSource read) {
        RoutingDataSource r = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.WRITE, write);
        targets.put(DataSourceType.READ, read);
        r.setTargetDataSources(targets);
        r.setDefaultTargetDataSource(write);
        return r;
    }

    @Bean @Primary
    public DataSource primaryDataSource(AbstractRoutingDataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }
}
