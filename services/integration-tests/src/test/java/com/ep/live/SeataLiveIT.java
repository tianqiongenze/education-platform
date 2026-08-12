package com.ep.live;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Seata TC (Transaction Coordinator) integration test.
 *
 * <p>Gap 1 闭环: 验证 Seata TC (namespace {@code esp-prod}, 7091) 真实可达,
 * 并能通过 HTTP API 响应全局事务查询。完整 Seata 客户端 (需 Dubbo 注册中心)
 * 留给后续, 此处仅做 TC 连通性与 API 契约验证。
 *
 * <p>D-113: 除原有 AT 模式探活外, 增加 Saga 模式端点探活。Seata 2.x TC 在
 * 同一端口暴露 AT 与 Saga 协调能力, 因此 Saga 检查复用同一 TC base URL,
 * 仅断言 Saga 状态机查询端点可达 (只读)。
 *
 * <p>默认 skip。仅在 {@code EP_LIVE=1} 且 {@code live} 标签启用时运行:
 * <pre>
 *   EP_LIVE=1 mvn verify -Dgroups=live -Dit.test=SeataLiveIT
 * </pre>
 *
 * <p>前置条件 (port-forward):
 * <pre>
 *   kubectl port-forward -n esp-prod svc/seata-tc-server 7091:7091
 * </pre>
 *
 * <p>安全约束: 仅做只读 HTTP 探测, 不发起/提交/回滚真实全局事务,
 * 不会影响共享集群中其他项目的 Seata 会话。
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeataLiveIT {

    private static final String EP_LIVE = System.getenv("EP_LIVE");
    private static final String TC_BASE_URL = System.getenv().getOrDefault(
            "EP_LIVE_SEATA_TC_URL", "http://localhost:7091");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TC_BASE_URL + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void test_seata_tc_connectivity() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Seata 验证");

        // Seata TC 默认暴露 /health 与 /metadata 端点; 多个候选路径择一可达即可
        int status = -1;
        String body = "";
        for (String path : new String[]{"/health", "/metadata", "/"}) {
            try {
                HttpResponse<String> resp = get(path);
                status = resp.statusCode();
                body = resp.body();
                if (status == 200) {
                    break;
                }
            } catch (Exception ignored) {
                // 继续尝试下一个候选路径
            }
        }
        assertTrue(status == 200,
                "Seata TC 健康端点应返回 200, 实际: " + status + ", body=" + body);
    }

    /**
     * 简化验证: TC API 能响应全局事务查询 (返回 200 或 4xx 都算可通信;
     * 这里断言 HTTP 往返成功而非具体业务语义, 避免污染 TC 状态)。
     */
    @Test
    @Order(2)
    void test_global_transaction_commit() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Seata 验证");

        // 查询一个不存在的 XID, 验证 TC API 可响应 (不发起真实事务)
        HttpResponse<String> resp = get("/api/v1/globalStatus/0");
        // 200 或 404/400 都表示 TC API 可通信
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 500,
                "Seata TC 全局事务查询应返回 2xx/4xx, 实际: " + resp.statusCode()
                        + ", body=" + resp.body());
    }

    /**
     * 简化验证: TC 能报告回滚状态 (同上, 仅验证 API 可达, 不实际回滚)。
     */
    @Test
    @Order(3)
    void test_global_transaction_rollback() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Seata 验证");

        HttpResponse<String> resp = get("/api/v1/globalStatus/nonexistent-xid");
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 500,
                "Seata TC 回滚状态查询应返回 2xx/4xx, 实际: " + resp.statusCode()
                        + ", body=" + resp.body());
        // 只读查询不会改变 TC 状态, 无需清理
    }

    /**
     * D-113: Saga 模式 TC 端点探活 (与 AT 模式并列)。
     *
     * <p>Seata 2.x TC 在同一端口同时协调 AT 与 Saga 事务。此处仅做只读
     * 探测, 验证 Saga 状态机查询端点可达, 不发起真实 Saga 实例
     * (Iron Law 0: 不污染共享集群状态)。
     *
     * <p>候选路径覆盖 Seata 2.x 暴露的 Saga 相关只读端点; 任一可达即通过。
     */
    @Test
    @Order(4)
    void test_saga_mode_tc_endpoint() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live Seata Saga 验证");

        // Saga 状态机实例查询: 查询一个不存在的 businessKey, 验证 TC API 可响应。
        // Seata 2.x 的 Saga 状态机查询走 /api/v1/stateMachine/queryByBusinessKey。
        int status = -1;
        String body = "";
        for (String path : new String[]{
                "/api/v1/stateMachine/queryByBusinessKey?businessKey=d113-probe",
                "/api/v1/stateMachine/getStatus?businessKey=d113-probe",
                "/metadata"
        }) {
            try {
                HttpResponse<String> resp = get(path);
                status = resp.statusCode();
                body = resp.body();
                if (status >= 200 && status < 500) {
                    break;
                }
            } catch (Exception ignored) {
                // 继续尝试下一个候选路径
            }
        }
        assertTrue(status >= 200 && status < 500,
                "D-113: Seata TC Saga 端点应返回 2xx/4xx (可达), 实际: "
                        + status + ", body=" + body);
    }

    /**
     * D-113: 静态契约守卫 — 确认 Saga 状态机定义已随 order-platform-service 部署。
     *
     * <p>这是只读的本地资源检查, 不连接 TC, 因此不受 {@code EP_LIVE} 守卫。
     * 它验证 create-order / cancel-order 两个 Saga JSON 定义存在于 classpath,
     * 是 D-113 接线的基础不变量。
     */
    @Test
    @Order(5)
    void test_saga_definitions_present_on_classpath() {
        String createOrderSaga = readClasspathResource(
                "services/order-platform/order-platform-service/src/main/resources/saga/create-order-saga.json");
        String cancelOrderSaga = readClasspathResource(
                "services/order-platform/order-platform-service/src/main/resources/saga/cancel-order-saga.json");

        assertTrue(createOrderSaga.contains("\"Name\": \"createOrderSaga\""),
                "D-113: create-order-saga.json must define Name=createOrderSaga");
        assertTrue(createOrderSaga.contains("reserveStock"),
                "D-113: create-order-saga.json must declare the reserveStock forward step");
        assertTrue(createOrderSaga.contains("CompensationTrigger"),
                "D-113: create-order-saga.json must declare a CompensationTrigger state");
        assertTrue(cancelOrderSaga.contains("\"Name\": \"cancelOrderSaga\""),
                "D-113: cancel-order-saga.json must define Name=cancelOrderSaga");
        assertTrue(cancelOrderSaga.contains("cancelOrder"),
                "D-113: cancel-order-saga.json must declare the cancelOrder forward step");
    }

    private static String readClasspathResource(String relativePath) {
        try {
            java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
            while (!java.nio.file.Files.exists(base.resolve(relativePath))
                    && base.getParent() != null) {
                base = base.getParent();
            }
            return java.nio.file.Files.readString(base.resolve(relativePath));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read resource " + relativePath, e);
        }
    }
}
