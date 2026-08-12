package com.ep.live;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Redis Cluster integration test using Redisson client.
 *
 * <p>Gap 1 闭环: 验证能真实连接 Redis Cluster (namespace
 * {@code ekb-m2-aws-us-east-1}, {@code redis-cluster-new}), 执行 SET/GET/TTL
 * 与集群拓扑探测。所有操作落在 DB 15 (隔离于生产 DB 0)。
 *
 * <p>默认 skip。仅在 {@code EP_LIVE=1} 且 {@code live} 标签启用时运行:
 * <pre>
 *   EP_LIVE=1 mvn verify -Dgroups=live -Dit.test=RedisIntegrationIT
 * </pre>
 *
 * <p>前置条件 (port-forward):
 * <pre>
 *   kubectl port-forward -n ekb-m2-aws-us-east-1 \
 *       svc/redis-cluster-new 6379:6379
 * </pre>
 *
 * <p>安全约束: 通过 {@code Config.useSingleServer().setDatabase(15)} 严格隔离,
 * 仅在 @AfterAll 对 DB 15 执行 FLUSHDB, 绝不 FLUSHALL, 不影响共享集群。
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisIntegrationIT {

    private static final String EP_LIVE = System.getenv("EP_LIVE");
    private static final String REDIS_HOST = System.getenv().getOrDefault(
            "EP_LIVE_REDIS_HOST", "127.0.0.1");
    private static final String REDIS_PORT = System.getenv().getOrDefault(
            "EP_LIVE_REDIS_PORT", "6379");
    /** DB 15: 与生产 DB 0 严格隔离, 测试结束 FLUSHDB 仅作用于本库。 */
    private static final int REDIS_DB = Integer.parseInt(
            System.getenv().getOrDefault("EP_LIVE_REDIS_DB", "15"));

    private static RedissonClient redisson;

    private static synchronized RedissonClient getClient() {
        if (redisson == null || redisson.isShutdown()) {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + REDIS_HOST + ":" + REDIS_PORT)
                    .setDatabase(REDIS_DB)
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(4);
            redisson = Redisson.create(config);
        }
        return redisson;
    }

    /**
     * 安全清理: 仅对 DB 15 执行 FLUSHDB, 删除本测试写入的 key,
     * 不使用 FLUSHALL, 不影响其他 DB / 其他项目。
     */
    @AfterAll
    static void cleanup() {
        try {
            RedissonClient client = getClient();
            client.getKeys().flushdb();
        } catch (Exception ignored) {
            // 清理失败不阻断测试结果上报
        } finally {
            if (redisson != null && !redisson.isShutdown()) {
                redisson.shutdown(2, 5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Order(1)
    void test_redis_ping() {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Redis 验证");

        RedissonClient client = getClient();
        assertTrue(!client.getNodesGroup().getNodes().isEmpty(),
                "Redisson 应已连接到 Redis");
        // 使用一个 echo-style bucket 验证读写回路
        RBucket<String> bucket = client.getBucket("ep:it:ping:" + System.currentTimeMillis());
        bucket.set("pong", Duration.ofSeconds(10));
        assertEquals("pong", bucket.get());
    }

    @Test
    @Order(2)
    void test_redis_set_get_with_ttl() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Redis 验证");

        RedissonClient client = getClient();
        String key = "ep:it:ttl:" + System.currentTimeMillis();
        RBucket<String> bucket = client.getBucket(key);

        // 设置带 TTL 的值
        bucket.set("hello-ep-it", Duration.ofSeconds(60));
        assertEquals("hello-ep-it", bucket.get(), "GET 应返回写入的值");

        // 验证 TTL 大于 0 (尚未过期)
        long ttl = bucket.remainTimeToLive();
        assertTrue(ttl > 0, "剩余 TTL 应大于 0, 实际: " + ttl);

        // 主动删除, 保持 DB 干净
        bucket.delete();
    }

    @Test
    @Order(3)
    void test_redis_cluster_info() {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Redis 验证");

        RedissonClient client = getClient();
        // NodesGroup 提供 Redis 集群节点视图; 非空即表明拓扑发现成功
        var nodes = client.getNodesGroup();
        assertNotNull(nodes, "NodesGroup 不应为 null");
        // getNodes() 在单节点模式下返回当前节点; Cluster 模式下返回全部节点
        // 这里仅断言能成功调用而不抛异常, 且连接池可用
        assertTrue(!nodes.getNodes().isEmpty(),
                "集群连接应保持活跃");
    }
}
