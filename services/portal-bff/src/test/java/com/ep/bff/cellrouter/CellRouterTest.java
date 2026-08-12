package com.ep.bff.cellrouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CellRouter 单元测试 — 纯单元测试, 无需 Spring 上下文.
 *
 * <p>覆盖路由一致性, misroute 纠正, 写挂起, TTL 过期, 原子更新等.
 */
class CellRouterTest {

    private RouteTable routeTable;
    private RouteContext routeContext;
    private WriteHoldRegistry writeHoldRegistry;
    private CellRouter cellRouter;

    @BeforeEach
    void setUp() throws Exception {
        routeTable = new RouteTable();
        setField(routeTable, "epDevOnly", "true");
        setField(routeTable, "configuredCells", "bj,sz,hz,sg");
        invokeMethod(routeTable, "init");

        // ObjectProvider that always returns null → no Redis in unit tests → in-memory path.
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> noRedis =
            new ObjectProvider<>() {
                @Override
                public org.springframework.data.redis.core.StringRedisTemplate getObject() {
                    return null;
                }
                @Override
                public org.springframework.data.redis.core.StringRedisTemplate getIfAvailable() {
                    return null;
                }
                @Override
                public org.springframework.data.redis.core.StringRedisTemplate getIfUnique() {
                    return null;
                }
            };

        routeContext = new RouteContext(noRedis);
        setField(routeContext, "epDevOnly", "true");

        writeHoldRegistry = new WriteHoldRegistry(noRedis);
        setField(writeHoldRegistry, "epDevOnly", "true");
        setField(writeHoldRegistry, "writeHoldTtlSeconds", 1800L);

        cellRouter = new CellRouter(routeTable, routeContext, writeHoldRegistry);
        cellRouter.setMisrouteLimit(3);
    }

    // --- 路由一致性 ---

    @Test
    @DisplayName("test_hash_route_consistency: 同一 userId 始终路由到同一 cell")
    void test_hash_route_consistency() {
        String userId = "user-12345";
        int first = routeTable.hashRoute(userId);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, routeTable.hashRoute(userId),
                "hashRoute must be deterministic for the same userId");
        }
    }

    @Test
    @DisplayName("test_different_users_different_cells: 多用户分布到不同 cell")
    void test_different_users_different_cells() {
        Set<Integer> cells = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            cells.add(routeTable.hashRoute("user-" + i));
        }
        assertTrue(cells.size() >= 2, "1000 users should distribute across at least 2 cells");
    }

    @Test
    @DisplayName("test_empty_user_id_fallback: null/empty userId → cell 0")
    void test_empty_user_id_fallback() {
        assertEquals(0, routeTable.hashRoute(null));
        assertEquals(0, routeTable.hashRoute(""));
        CellInfo info = routeTable.getCell(0);
        assertNotNull(info);
        assertEquals("cell-bj", info.cellName());
    }

    // --- misroute 纠正 ---

    @Test
    @DisplayName("test_correct_cell_passes: 到达正确 cell → Pass")
    void test_correct_cell_passes() {
        String userId = "test-user-correct";
        int targetIndex = routeTable.hashRoute(userId);
        CellInfo target = routeTable.getCell(targetIndex);

        RouteResult result = cellRouter.route(userId, target.cellName(), "GET");
        assertInstanceOf(RouteResult.Pass.class, result);
        assertEquals(target.cellName(), ((RouteResult.Pass) result).cell().cellName());
    }

    @Test
    @DisplayName("test_misroute_redirect_within_limit: 3 次连续 misroute → Redirect (未超限)")
    void test_misroute_redirect_within_limit() {
        String userId = "misroute-within";
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));
        String wrongCell = findWrongCell(target.cellName());

        for (int i = 1; i <= 3; i++) {
            RouteResult result = cellRouter.route(userId, wrongCell, "GET");
            assertInstanceOf(RouteResult.Redirect.class, result,
                "misroute #" + i + " should redirect (within limit)");
        }
        assertEquals(3, routeContext.getCorrectCount(userId));
    }

    @Test
    @DisplayName("test_misroute_exceed_limit: 第 4 次 misroute → Reject(403)")
    void test_misroute_exceed_limit() {
        String userId = "misroute-exceed";
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));
        String wrongCell = findWrongCell(target.cellName());

        // 前 3 次重定向
        for (int i = 0; i < 3; i++) {
            RouteResult r = cellRouter.route(userId, wrongCell, "GET");
            assertInstanceOf(RouteResult.Redirect.class, r);
        }
        // 第 4 次 → Reject
        RouteResult result = cellRouter.route(userId, wrongCell, "GET");
        assertInstanceOf(RouteResult.Reject.class, result);
        assertEquals(403, ((RouteResult.Reject) result).statusCode());
    }

    @Test
    @DisplayName("test_route_context_clear_on_correct_route: 到达正确 cell 后计数重置")
    void test_route_context_clear_on_correct_route() {
        String userId = "clear-test";
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));
        String wrongCell = findWrongCell(target.cellName());

        // 制造 2 次 misroute
        cellRouter.route(userId, wrongCell, "GET");
        cellRouter.route(userId, wrongCell, "GET");
        assertEquals(2, routeContext.getCorrectCount(userId));

        // 到达正确 cell → 计数清零
        cellRouter.route(userId, target.cellName(), "GET");
        assertEquals(0, routeContext.getCorrectCount(userId));

        // 再次 misroute, 计数从 1 重新开始
        RouteResult r = cellRouter.route(userId, wrongCell, "GET");
        assertInstanceOf(RouteResult.Redirect.class, r);
        assertEquals(1, routeContext.getCorrectCount(userId));
    }

    @Test
    @DisplayName("test_single_cell_always_pass: 仅 1 个 cell → 始终 Pass (无 misroute 可能)")
    void test_single_cell_always_pass() throws Exception {
        // 手动替换快照为单 cell
        RouteTable.CellConfigSnapshot single = new RouteTable.CellConfigSnapshot(
            java.util.List.of(new CellInfo(0, "cell-only", "cn-east-1", "bj", CellInfo.CellStatus.ACTIVE)),
            2, System.currentTimeMillis());
        setField(routeTable, "snapshot", single);

        String userId = "single-cell-user";
        for (int i = 0; i < 5; i++) {
            RouteResult result = cellRouter.route(userId, "cell-only", "POST");
            assertInstanceOf(RouteResult.Pass.class, result, "single cell should always pass");
        }
    }

    // --- 写挂起 ---

    @Test
    @DisplayName("test_write_hold_returns_503: POST + isHeld → Hold(503)")
    void test_write_hold_returns_503() {
        String userId = "held-user";
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));

        writeHoldRegistry.hold(userId);
        assertTrue(writeHoldRegistry.isHeld(userId));

        RouteResult result = cellRouter.route(userId, target.cellName(), "POST");
        assertInstanceOf(RouteResult.Hold.class, result);
        assertEquals(503, ((RouteResult.Hold) result).statusCode());
    }

    @Test
    @DisplayName("test_read_passes_during_hold: GET + isHeld → Pass (仅写挂起)")
    void test_read_passes_during_hold() {
        String userId = "read-during-hold";
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));

        writeHoldRegistry.hold(userId);
        RouteResult result = cellRouter.route(userId, target.cellName(), "GET");
        assertInstanceOf(RouteResult.Pass.class, result,
            "GET should pass even when write-hold is active");
    }

    @Test
    @DisplayName("test_write_hold_release: hold 后 release → 写放行")
    void test_write_hold_release() {
        String userId = "release-test";
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));

        writeHoldRegistry.hold(userId);
        RouteResult held = cellRouter.route(userId, target.cellName(), "PUT");
        assertInstanceOf(RouteResult.Hold.class, held);

        writeHoldRegistry.release(userId);
        assertFalse(writeHoldRegistry.isHeld(userId));

        RouteResult released = cellRouter.route(userId, target.cellName(), "PUT");
        assertInstanceOf(RouteResult.Pass.class, released);
    }

    @Test
    @DisplayName("test_write_hold_ttl_expiry: 超过 TTL 的条目自动驱逐")
    void test_write_hold_ttl_expiry() throws Exception {
        String userId = "ttl-user";
        // 设置短 TTL = 1 秒
        setField(writeHoldRegistry, "writeHoldTtlSeconds", 1L);
        // 手动插入一个已过期的挂起条目 (2 秒前)
        writeHoldRegistry.holdAt(userId, System.currentTimeMillis() - 2000);

        assertFalse(writeHoldRegistry.isHeld(userId), "expired entry should not be held");
        assertTrue(writeHoldRegistry.getHeldUsers().isEmpty(), "expired entry should be evicted");

        // 写请求应放行
        CellInfo target = routeTable.getCell(routeTable.hashRoute(userId));
        RouteResult result = cellRouter.route(userId, target.cellName(), "DELETE");
        assertInstanceOf(RouteResult.Pass.class, result);
    }

    // --- 路由表原子更新 ---

    @Test
    @DisplayName("test_route_table_atomic_update: onConfigChange 替换快照, 新路由使用新配置")
    void test_route_table_atomic_update() throws Exception {
        // EP_DEV_ONLY=true → onConfigChange no-op; 临时关闭以测试生产路径
        setField(routeTable, "epDevOnly", "false");

        int originalVersion = routeTable.getSnapshot().version();
        routeTable.onConfigChange("bj,sz"); // 仅 2 个 cell

        RouteTable.CellConfigSnapshot snap = routeTable.getSnapshot();
        assertEquals(originalVersion + 1, snap.version());
        assertEquals(2, snap.cells().size());
        assertEquals("cell-bj", snap.cells().get(0).cellName());
        assertEquals("cell-sz", snap.cells().get(1).cellName());

        // 新路由使用新配置: cell 数量变为 2
        assertEquals(2, routeTable.getCellCount());
        int idx = routeTable.hashRoute("atomic-user");
        assertTrue(idx == 0 || idx == 1, "index must be within new cell range");
    }

    @Test
    @DisplayName("test_route_table_dev_only_noop: EP_DEV_ONLY 时 onConfigChange 不修改快照")
    void test_route_table_dev_only_noop() {
        int originalVersion = routeTable.getSnapshot().version();
        routeTable.onConfigChange("bj,sz");
        assertEquals(originalVersion, routeTable.getSnapshot().version(),
            "EP_DEV_ONLY should make onConfigChange a no-op");
    }

    @Test
    @DisplayName("test_get_cell_by_name: 按名查找单元")
    void test_get_cell_by_name() {
        Optional<CellInfo> bj = routeTable.getCellByName("cell-bj");
        assertTrue(bj.isPresent());
        assertEquals(0, bj.get().cellId());
        assertEquals("cn-east-1", bj.get().region());

        assertTrue(routeTable.getCellByName("nonexistent").isEmpty());
        assertTrue(routeTable.getCellByName(null).isEmpty());
        assertTrue(routeTable.getCellByName("").isEmpty());
    }

    // --- isWriteMethod 分类 ---

    @Test
    @DisplayName("test_is_write_method_classification: POST/PUT/PATCH/DELETE=写, GET/HEAD/OPTIONS=读")
    void test_is_write_method_classification() {
        assertTrue(CellRouter.isWriteMethod("POST"));
        assertTrue(CellRouter.isWriteMethod("PUT"));
        assertTrue(CellRouter.isWriteMethod("PATCH"));
        assertTrue(CellRouter.isWriteMethod("DELETE"));
        // 大小写不敏感
        assertTrue(CellRouter.isWriteMethod("post"));
        assertTrue(CellRouter.isWriteMethod("Put"));

        assertFalse(CellRouter.isWriteMethod("GET"));
        assertFalse(CellRouter.isWriteMethod("HEAD"));
        assertFalse(CellRouter.isWriteMethod("OPTIONS"));
        assertFalse(CellRouter.isWriteMethod("get"));

        // 边界
        assertFalse(CellRouter.isWriteMethod(null));
        assertFalse(CellRouter.isWriteMethod(""));
    }

    @Test
    @DisplayName("test_non_cell_deployment_skips_misroute: currentCell=null → 直接 Pass")
    void test_non_cell_deployment_skips_misroute() {
        String userId = "no-cell-user";
        // currentCell = null (非单元部署), 即使 userId 路由到任意 cell 也直接放行
        RouteResult result = cellRouter.route(userId, null, "GET");
        assertInstanceOf(RouteResult.Pass.class, result);

        RouteResult emptyCell = cellRouter.route(userId, "", "GET");
        assertInstanceOf(RouteResult.Pass.class, emptyCell);
    }

    @Nested
    @DisplayName("WriteHoldRegistry 直接测试")
    class WriteHoldRegistryDirectTests {

        @Test
        @DisplayName("getHeldUsers 返回当前挂起的用户快照")
        void getHeldUsers_returnsSnapshot() {
            writeHoldRegistry.hold("u1");
            writeHoldRegistry.hold("u2");
            Set<String> held = writeHoldRegistry.getHeldUsers();
            assertTrue(held.contains("u1"));
            assertTrue(held.contains("u2"));
            assertEquals(2, held.size());

            writeHoldRegistry.release("u1");
            assertFalse(writeHoldRegistry.getHeldUsers().contains("u1"));
        }

        @Test
        @DisplayName("hold 空用户 no-op")
        void hold_nullOrEmpty_isNoOp() {
            writeHoldRegistry.hold(null);
            writeHoldRegistry.hold("");
            assertTrue(writeHoldRegistry.getHeldUsers().isEmpty());
            assertFalse(writeHoldRegistry.isHeld(null));
            assertFalse(writeHoldRegistry.isHeld(""));
        }
    }

    // --- helpers ---

    /** 找一个与给定 cell 名不同的 cell 名, 用于制造 misroute. */
    private String findWrongCell(String correctCell) {
        for (int i = 0; i < routeTable.getCellCount(); i++) {
            String name = routeTable.getCell(i).cellName();
            if (!name.equals(correctCell)) {
                return name;
            }
        }
        throw new IllegalStateException("no alternate cell available");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeMethod(Object target, String methodName) throws Exception {
        var m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }
}
