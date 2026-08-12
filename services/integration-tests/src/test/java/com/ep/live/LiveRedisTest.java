package com.ep.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration test against the real Redis Cluster deployed in K8s
 * (namespace {@code ekb-m2-aws-us-east-1}, {@code redis-cluster-new} 6 节点).
 *
 * <p>D-048 §11.4 诚实声明 #1 闭环 (Java 侧): 验证能真实连接 Redis Cluster,
 * 执行 PING 与 CLUSTER INFO 探测。
 *
 * <p>默认 skip。仅在 JUnit 标签 {@code live} 显式启用时运行:
 * <pre>
 *   mvn test -Dgroups=live -Dtest=LiveRedisTest
 * </pre>
 *
 * <p>前置条件: 通过 port-forward 或在集群内可达:
 * <pre>
 *   kubectl port-forward -n ekb-m2-aws-us-east-1 \
 *       svc/redis-cluster-new 6379:6379
 * </pre>
 *
 * <p>说明: 完整的 Redisson 客户端 (含 Cluster 拓扑发现) 验证留给
 * {@code LiveRedissonSeataTest} (诚实声明 #2), 本类仅做基础连通性验证,
 * 使用 JDK 内置 HttpClient 经 Redis 的 HTTP 健康端口或命令行 redis-cli 兜底。
 * 由于 Redis 无原生 HTTP 接口, 此处通过环境变量 {@code EP_LIVE} 控制,
 * 未启用时直接 assumeTrue 跳过, 不引入 redisson 测试依赖。
 */
@Tag("live")
class LiveRedisTest {

    private static final String EP_LIVE = System.getenv("EP_LIVE");

    @Test
    void redisClusterPingViaCli() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Redis 验证");

        ProcessBuilder pb = new ProcessBuilder(
                "kubectl", "exec", "-n", "ekb-m2-aws-us-east-1",
                "redis-cluster-new-0", "--", "redis-cli", "-c", "ping");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(Duration.ofSeconds(30).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(true, finished, "kubectl exec 超时");
        assertEquals(0, p.exitValue(), "kubectl exec 失败: " + out);
        assertEquals("PONG", out, "Redis PING 应返回 PONG");
    }

    @Test
    void redisClusterInfoStateOk() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Redis 验证");

        ProcessBuilder pb = new ProcessBuilder(
                "kubectl", "exec", "-n", "ekb-m2-aws-us-east-1",
                "redis-cluster-new-0", "--", "redis-cli", "-c", "CLUSTER", "INFO");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor(Duration.ofSeconds(30).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        String out = new String(p.getInputStream().readAllBytes());
        assertNotNull(out, "CLUSTER INFO 输出不应为空");
        assertEquals(true, out.contains("cluster_state:ok"),
                "cluster_state 应为 ok, 实际: " + out);
    }
}
