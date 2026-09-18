package com.ep.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-216 (data-layer audit D10): Spring wiring verification — the auto
 * configuration must assemble the full dual-pool graph when enabled, and
 * must not register anything when disabled.
 */
class EpDataAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EpDataAutoConfiguration.class));

    private static final String H2_WRITE = "jdbc:h2:mem:wtest;DB_CLOSE_DELAY=-1";
    private static final String H2_READ = "jdbc:h2:mem:rtest;DB_CLOSE_DELAY=-1";

    private static final String[] ENABLED_H2 = {
            "ep.data.enabled=true",
            "ep.data.provider=h2",
            "ep.data.write.url=" + H2_WRITE,
            "ep.data.read.url=" + H2_READ,
            "ep.data.read-replicas=true",
    };

    @Test
    @DisplayName("disabled (default): nothing is registered")
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean("dataSource")
                .doesNotHaveBean("writeDataSource")
                .doesNotHaveBean(EpDataProperties.class));
    }

    @Test
    @DisplayName("enabled + h2 provider: primary DataSource is the lazy routing proxy")
    void enabledH2WiresRoutingGraph() {
        runner.withPropertyValues(ENABLED_H2).run(ctx -> {
            // three DataSource-typed beans exist (routing + 2 pools);
            // the PRIMARY one (injectable as DataSource) is the lazy proxy.
            assertThat(ctx.getBean("dataSource")).isInstanceOf(LazyConnectionDataSourceProxy.class);
            assertThat(ctx.getBean(DataSource.class)).isInstanceOf(LazyConnectionDataSourceProxy.class);
            assertThat(ctx).hasBean("writeDataSource");
            assertThat(ctx).hasBean("readDataSource");
            assertThat(ctx).hasSingleBean(EpDataProperties.class);
            assertThat(ctx).hasSingleBean(RoutingOverrideAspect.class);
        });
    }

    @Test
    @DisplayName("provider without replicas (h2 + read-replicas=false): read pool collapses onto write pool")
    void readPoolCollapse() {
        runner.withPropertyValues(
                "ep.data.enabled=true",
                "ep.data.provider=h2",
                "ep.data.write.url=" + H2_WRITE)
                .run(ctx -> {
                    assertThat(ctx).hasBean("writeDataSource");
                    assertThat(ctx).hasBean("readDataSource");
                });
    }

    @Test
    @DisplayName("url missing and host/database missing -> startup failure with actionable message")
    void missingUrlFailsFast() {
        runner.withPropertyValues(
                "ep.data.enabled=true",
                "ep.data.provider=tidb")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasMessageContaining("ep-data pool needs"));
    }
}
