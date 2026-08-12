package com.ep.bff.edge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RouteTable 单元测试 — 覆盖 D-068 双路由系统调和.
 *
 * <p>System A (admin-web 直连, 带版本前缀 /api/v{n}/) 与 System B (student app → BFF,
 * 无版本前缀) 的请求必须都能正确解析到下游服务。
 */
class RouteTableTest {
    @Test
    void resolvesLearningRoute() {
        assertEquals("core-business:80", RouteTable.resolve("/api/learning/123"));
    }

    @Test
    void resolvesWsRoute() {
        assertEquals("ws-gateway:80", RouteTable.resolve("/api/ws/connect"));
    }

    @Test
    void resolvesAiRoute() {
        assertEquals("ai-platform:80", RouteTable.resolve("/api/ai/chat"));
    }

    // ------------------------------------------------------------------
    // D-149 — 康养养老 + 棋牌教育+对弈 事业群路由
    // ------------------------------------------------------------------

    @Test
    void resolvesElderRoute() {
        // D-149: 康养养老事业群 → senior-care
        assertEquals("senior-care:80", RouteTable.resolve("/api/elder/profiles/123"));
    }

    @Test
    void resolvesGameRoute() {
        // D-149: 棋牌教育+对弈事业群 → boardgame-service
        assertEquals("boardgame-service:80", RouteTable.resolve("/api/game/rooms/456"));
    }

    @Test
    void resolveServiceHandlesSystemAElderRoute() {
        // System A: /api/v1/elder/... → senior-care
        assertEquals("senior-care:80", RouteTable.resolveService("/api/v1/elder/care-plans/9"));
    }

    @Test
    void resolveServiceHandlesSystemBElderRoute() {
        // System B: /api/elder/... → senior-care
        assertEquals("senior-care:80", RouteTable.resolveService("/api/elder/health-records/9"));
    }

    @Test
    void resolveServiceHandlesSystemAGameRoute() {
        // System A: /api/v1/game/matches/... → boardgame-service
        assertEquals("boardgame-service:80", RouteTable.resolveService("/api/v1/game/matches/42"));
    }

    @Test
    void resolveServiceHandlesSystemBGameRoute() {
        // System B: /api/game/matches/... → boardgame-service
        assertEquals("boardgame-service:80", RouteTable.resolveService("/api/game/matches/42"));
    }

    @Test
    void returnsNullForUnknownRoute() {
        assertNull(RouteTable.resolve("/api/unknown"));
    }

    @Test
    void isLocalRecognizesAuthPath() {
        assertTrue(RouteTable.isLocal("/api/auth/login"));
        assertTrue(RouteTable.isLocal("/healthz"));
        assertTrue(RouteTable.isLocal("/actuator/health"));
    }

    @Test
    void isLocalReturnsFalseForNonLocal() {
        assertFalse(RouteTable.isLocal("/api/course/1"));
    }

    @Test
    void getAllReturnsImmutableMap() {
        var routes = RouteTable.getAll();
        assertNotNull(routes);
        assertTrue(routes.containsKey("/api/live"));
        assertThrows(UnsupportedOperationException.class, () -> routes.put("x", "y"));
    }

    // ------------------------------------------------------------------
    // D-068 — 双路由系统调和 (Dual-System Reconciliation)
    // ------------------------------------------------------------------

    @Test
    void resolveServiceHandlesSystemAVersionedUsers() {
        // System A: admin-web → core-business 直连, /api/v1/users
        assertEquals("core-business:80", RouteTable.resolveService("/api/v1/users"));
    }

    @Test
    void resolveServiceHandlesSystemBUnversionedUsers() {
        // System B: student app → portal-bff → Dubbo RPC, /api/users
        assertEquals("core-business:80", RouteTable.resolveService("/api/users"));
    }

    @Test
    void resolveServiceHandlesSystemAVersionedCoursesWithId() {
        assertEquals("core-business:80", RouteTable.resolveService("/api/v1/courses/123"));
    }

    @Test
    void resolveServiceHandlesSystemAVersionedAssignments() {
        assertEquals("grading-service:80", RouteTable.resolveService("/api/v1/assignments/456"));
    }

    @Test
    void resolveServiceHandlesSystemBUnversionedGradingStart() {
        // System B 风格: /api/grading/start 走 grading-service
        assertEquals("grading-service:80", RouteTable.resolveService("/api/grading/start"));
    }

    @Test
    void resolveServiceReturnsNullForUnknownPath() {
        assertNull(RouteTable.resolveService("/api/totally-unknown/xyz"));
    }

    @Test
    void resolveServiceBackwardCompatibleWithLegacyResolve() {
        // 旧入口 resolve() 必须与新入口 resolveService() 行为一致, 保证既有调用方不破坏.
        assertEquals(RouteTable.resolve("/api/learning/123"), RouteTable.resolveService("/api/learning/123"));
        assertEquals(RouteTable.resolve("/api/v1/grading/start"), RouteTable.resolveService("/api/v1/grading/start"));
    }

    @Test
    void resolveServiceHandlesHigherVersionPrefix() {
        // 未来版本号 (v2/v3) 也应被归一化, 不影响路由匹配.
        assertEquals("core-business:80", RouteTable.resolveService("/api/v2/users/9"));
        assertEquals("grading-service:80", RouteTable.resolveService("/api/v3/assignments/9"));
    }

    @Test
    void resolveServicePicksLongestMatchingPrefix() {
        // /api/users 与 /api/courses 是不同 key, 各自命中 core-business.
        // /api/grading 与 /api/grades 不混淆: /api/grading 命中 grading-service,
        // /api/grades 不会误命中 (无对应 key, 但可能命中更短前缀, 这里验证 grading 优先).
        assertEquals("grading-service:80", RouteTable.resolveService("/api/grading/anything"));
        assertEquals("core-business:80", RouteTable.resolveService("/api/courses/123"));
    }

    @Test
    void isLocalHandlesVersionedAuthPath() {
        // System A 风格的鉴权路径也应被视为本地处理.
        assertTrue(RouteTable.isLocal("/api/v1/auth/login"));
    }

    @Test
    void resolveServiceReturnsNullForNullInput() {
        assertNull(RouteTable.resolveService(null));
    }
}
