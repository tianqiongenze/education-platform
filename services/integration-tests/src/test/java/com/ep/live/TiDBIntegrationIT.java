package com.ep.live;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live JDBC integration test against the real TiDB deployed in K8s
 * (namespace {@code ekb-m2-aws-us-east-1}, MySQL 协议 4000).
 *
 * <p>Gap 1 闭环: 验证能通过 JDBC 真实连接 TiDB, 执行 DDL/DML/CTE,
 * 所有测试数据写入隔离 schema {@code ep_test_integration}, 测试结束清理。
 *
 * <p>默认 skip。仅在 {@code EP_LIVE=1} 且 failsafe {@code live} 标签启用时运行:
 * <pre>
 *   EP_LIVE=1 mvn verify -Dgroups=live -Dit.test=TiDBIntegrationIT
 * </pre>
 *
 * <p>前置条件 (port-forward):
 * <pre>
 *   kubectl port-forward -n ekb-m2-aws-us-east-1 \
 *       svc/tidb-cluster-tidb 4000:4000
 * </pre>
 *
 * <p>安全约束: 所有 DDL 仅作用于 {@code ep_test_integration} schema,
 * 表名带时间戳前缀避免与其他测试冲突, 测试结束 DROP TABLE 清理。
 * 不会影响共享集群中其他项目的数据。
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TiDBIntegrationIT {

    private static final String EP_LIVE = System.getenv("EP_LIVE");
    private static final String TIDB_URL = System.getenv().getOrDefault(
            "EP_LIVE_TIDB_URL",
            "jdbc:mysql://127.0.0.1:4000/ep_test_integration");
    private static final String TIDB_USER = System.getenv().getOrDefault(
            "EP_LIVE_TIDB_USER", "root");
    private static final String TIDB_PASSWORD = System.getenv().getOrDefault(
            "EP_LIVE_TIDB_PASSWORD", "");

    private static final String SCHEMA = "ep_test_integration";
    /** 一次性时间戳用于本测试类生成的唯一表名, 避免并发/重跑冲突。 */
    private static final String TS = String.valueOf(System.currentTimeMillis());
    private static final String TABLE_CRUD = "ep_test_it_crud_" + TS;
    private static final String TABLE_CTE = "ep_test_it_cte_" + TS;

    /**
     * 安全清理: 即使测试失败也确保删除本类创建的临时表, 避免遗留垃圾对象。
     * 仅 DROP 本类已知表名, 不使用 DROP DATABASE, 不影响共享集群。
     */
    @AfterAll
    static void cleanup() {
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS `" + SCHEMA + "`.`" + TABLE_CRUD + "`");
            stmt.execute("DROP TABLE IF EXISTS `" + SCHEMA + "`.`" + TABLE_CTE + "`");
        } catch (SQLException ignored) {
            // 清理失败不阻断测试结果上报
        }
    }

    private static Connection openConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", TIDB_USER);
        if (TIDB_PASSWORD != null && !TIDB_PASSWORD.isEmpty()) {
            props.setProperty("password", TIDB_PASSWORD);
        }
        return DriverManager.getConnection(TIDB_URL, props);
    }

    @Test
    @Order(1)
    void test_tidb_connection_and_version() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live TiDB 验证");

        // 先确保隔离 schema 存在 (CREATE DATABASE 不破坏既有数据)
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS `" + SCHEMA + "`");
        }

        // 重新连接并验证版本标识为 TiDB
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            assertTrue(conn.isValid(5), "TiDB JDBC 连接应有效");

            String version;
            try (ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
                assertTrue(rs.next(), "VERSION() 应返回一行");
                version = rs.getString(1);
            }
            assertTrue(version != null && version.contains("TiDB"),
                    "版本字符串应包含 TiDB, 实际: " + version);
        }
    }

    @Test
    @Order(2)
    void test_create_table_and_crud() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live TiDB 验证");

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // DDL: 在隔离 schema 内创建带时间戳的唯一表
            stmt.execute("CREATE TABLE IF NOT EXISTS `" + SCHEMA + "`.`" + TABLE_CRUD + "` ("
                    + "id BIGINT PRIMARY KEY, "
                    + "name VARCHAR(64) NOT NULL, "
                    + "value INT DEFAULT 0)");

            // INSERT
            int inserted = stmt.executeUpdate(
                    "INSERT INTO `" + SCHEMA + "`.`" + TABLE_CRUD + "` (id, name, value) "
                            + "VALUES (1, 'ep-it', 100)");
            assertEquals(1, inserted, "INSERT 应影响 1 行");

            // SELECT
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, name, value FROM `" + SCHEMA + "`.`" + TABLE_CRUD
                            + "` WHERE id = 1")) {
                assertTrue(rs.next(), "应查询到刚插入的行");
                assertEquals(1L, rs.getLong("id"));
                assertEquals("ep-it", rs.getString("name"));
                assertEquals(100, rs.getInt("value"));
            }

            // UPDATE
            int updated = stmt.executeUpdate(
                    "UPDATE `" + SCHEMA + "`.`" + TABLE_CRUD
                            + "` SET value = 200 WHERE id = 1");
            assertEquals(1, updated, "UPDATE 应影响 1 行");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT value FROM `" + SCHEMA + "`.`" + TABLE_CRUD
                            + "` WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(200, rs.getInt("value"));
            }

            // DELETE
            int deleted = stmt.executeUpdate(
                    "DELETE FROM `" + SCHEMA + "`.`" + TABLE_CRUD + "` WHERE id = 1");
            assertEquals(1, deleted, "DELETE 应影响 1 行");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM `" + SCHEMA + "`.`" + TABLE_CRUD + "`")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "DELETE 后表应为空");
            }
        }
    }

    /**
     * D-053 程序性记忆依赖: 验证 TiDB 支持递归 CTE (RECURSIVE),
     * 这是评分流程/审计回溯场景用到的 SQL 能力。
     */
    @Test
    @Order(3)
    void test_tidb_recursive_cte() throws Exception {
        assumeTrue("1".equals(EP_LIVE), "EP_LIVE 未启用, 跳过 live TiDB 验证");

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS `" + SCHEMA + "`.`" + TABLE_CTE + "` ("
                    + "id BIGINT PRIMARY KEY)");

            // 插入若干行用于递归
            stmt.execute("INSERT INTO `" + SCHEMA + "`.`" + TABLE_CTE + "` (id) "
                    + "VALUES (1),(2),(3),(4),(5)");

            // WITH RECURSIVE 求和 1..5 = 15
            try (ResultSet rs = stmt.executeQuery(
                    "WITH RECURSIVE r(n) AS ("
                            + "  SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n < 5"
                            + ") SELECT SUM(n) AS s FROM r")) {
                assertTrue(rs.next(), "递归 CTE 应返回一行");
                assertEquals(15, rs.getInt("s"), "1..5 求和应为 15");
            }

            // 清理本测试数据, 表本身由 @AfterAll 删除
            stmt.execute("DELETE FROM `" + SCHEMA + "`.`" + TABLE_CTE + "`");
        }
    }
}
