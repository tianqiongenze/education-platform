package com.ep.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration test against the real TiDB deployed in K8s
 * (namespace {@code ekb-m2-aws-us-east-1}, {@code tidb-cluster-tidb},
 *  MySQL 协议 4000 / Status API 10080).
 *
 * <p>D-048 §11.4 诚实声明 #1 闭环 (Java 侧): 验证 TiDB Status API 真实可达,
 * 并返回 {@code 8.0.11-TiDB-v8.5.5} 版本字符串。
 *
 * <p>默认 skip。仅在 {@code -Dgroups=live} 时运行:
 * <pre>
 *   mvn test -Dgroups=live -Dtest=LiveTiDBTest
 * </pre>
 *
 * <p>说明: 完整的 JDBC 端到端验证 (含事务回滚) 留给后续 GradingService
 * Dubbo API 发布后 (诚实声明 #3), 本类通过 Status API (10080, 无需 mysql
 * 客户端依赖) 验证 TiDB 实际可达性。Pod 内无 mysql CLI, 故不走 MySQL 协议。
 *
 * <p>前置条件: 通过 port-forward 或在集群内可达:
 * <pre>
 *   kubectl port-forward -n ekb-m2-aws-us-east-1 \
 *       svc/tidb-cluster-tidb 10080:10080
 * </pre>
 */
@Tag("live")
class LiveTiDBTest {

    private static final String EP_LIVE = System.getenv("EP_LIVE");
    private static final String STATUS_URL =
            System.getenv().getOrDefault("TIDB_STATUS_URL",
                    "http://localhost:10080/status");

    @Test
    void tidbStatusApiReachable() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live TiDB 验证");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(STATUS_URL))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req,
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(),
                "TiDB status API 应返回 200, 实际: " + resp.statusCode());
        String body = resp.body();
        assertTrue(body.contains("TiDB-v"),
                "status 应包含 TiDB 版本号, 实际: " + body);
        assertTrue(body.contains("\"connections\""),
                "status 应包含 connections 字段, 实际: " + body);
    }
}
