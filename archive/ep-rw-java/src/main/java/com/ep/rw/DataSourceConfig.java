package com.ep.rw;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * D-041: 读写分离 + TiDB Follower Read 多区域路由配置。
 *
 * <p>三个数据源：
 * <ul>
 *   <li>WRITE — 写主库（TiDB Leader 副本，本地 cell）</li>
 *   <li>READ — 读从库（TiDB Follower，本地 cell，低延迟读）</li>
 *   <li>FOLLOWER_READ — 跨单元 TiDB Follower Read（异地 follower 副本，
 *       用于多活场景下就近读取，通过 TiDB {@code tidb_replica_read=follower}
 *       连接参数实现）</li>
 * </ul>
 *
 * <p>当 {@code ep.rw.follower-read.enabled=false}（默认）时，
 * FOLLOWER_READ 数据源不创建，路由到 FOLLOWER_READ 时降级为 READ。
 *
 * @deprecated D-094 (2026-08-08): ep-rw-java has 0 consumers across the
 * monorepo. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-rw-java/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
@Configuration
@ConditionalOnProperty(name = "ep.rw.enabled", matchIfMissing = true)
public class DataSourceConfig {
    @Bean @ConfigurationProperties("spring.datasource.write")
    public DataSource writeDataSource() { return new HikariDataSource(); }

    @Bean @ConfigurationProperties("spring.datasource.read")
    public DataSource readDataSource() { return new HikariDataSource(); }

    /**
     * D-041: 跨单元 Follower Read 数据源。
     * 仅当 {@code ep.rw.follower-read.enabled=true} 时创建。
     * JDBC URL 需含 {@code tidb_replica_read=follower} 参数。
     */
    @Bean
    @ConfigurationProperties("spring.datasource.follower-read")
    @ConditionalOnProperty(name = "ep.rw.follower-read.enabled", havingValue = "true")
    public DataSource followerReadDataSource() { return new HikariDataSource(); }

    @Bean
    public AbstractRoutingDataSource routingDataSource(
            @Qualifier("writeDataSource") DataSource write,
            @Qualifier("readDataSource") DataSource read,
            ObjectProvider<DataSource> followerReadProvider) {
        RoutingDataSource r = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.WRITE, write);
        targets.put(DataSourceType.READ, read);
        // D-041: 如果 follower-read 数据源存在则注册，否则降级为 READ
        // 使用 ObjectProvider 避免循环依赖（followerReadDataSource bean 可选）
        DataSource followerRead = followerReadProvider.getIfAvailable();
        if (followerRead != null) {
            targets.put(DataSourceType.FOLLOWER_READ, followerRead);
        } else {
            targets.put(DataSourceType.FOLLOWER_READ, read);
        }
        r.setTargetDataSources(targets);
        r.setDefaultTargetDataSource(write);
        return r;
    }

    @Bean @Primary
    public DataSource primaryDataSource(@Lazy AbstractRoutingDataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }
}
