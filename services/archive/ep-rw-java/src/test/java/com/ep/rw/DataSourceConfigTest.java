package com.ep.rw;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceConfigTest {
    @Test void wiresRoutingDataSourceAsPrimary() {
        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues(
                "spring.datasource.write.jdbc-url=jdbc:h2:mem:w",
                "spring.datasource.write.username=sa",
                "spring.datasource.write.password=",
                "spring.datasource.read.jdbc-url=jdbc:h2:mem:r",
                "spring.datasource.read.username=sa",
                "spring.datasource.read.password="
            )
            .run(ctx -> {
                assertNotNull(ctx.getBean(DataSource.class));
                assertTrue(ctx.getBean(DataSource.class) instanceof AbstractRoutingDataSource
                    || ctx.getBean(DataSource.class).getClass().getName().contains("Lazy"));
            });
    }
    @Test void disabledWhenFlagFalse() {
        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues("ep.rw.enabled=false",
                "spring.datasource.write.jdbc-url=jdbc:h2:mem:w",
                "spring.datasource.write.username=sa","spring.datasource.write.password=",
                "spring.datasource.read.jdbc-url=jdbc:h2:mem:r",
                "spring.datasource.read.username=sa","spring.datasource.read.password=")
            .run(ctx -> assertFalse(ctx.containsBean("routingDataSource")));
    }
}
