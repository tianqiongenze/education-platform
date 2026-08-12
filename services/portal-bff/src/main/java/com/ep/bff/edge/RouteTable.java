package com.ep.bff.edge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路由表 — 前缀 → 下游服务短名:80 (单元内 ClusterIP).
 * 生产环境从 Nacos 动态加载, 此为 fallback 静态表.
 *
 * <h3>D-068 — 双路由系统调和 (API Routing Dual-System Reconciliation)</h3>
 *
 * 平台并存两套路由风格:
 * <ul>
 *   <li><b>System A (admin-web → core-business 直连)</b>: 带版本前缀, 形如
 *       {@code /api/v1/users}, {@code /api/v1/courses/123}, {@code /api/v1/orders}.</li>
 *   <li><b>System B (student app → portal-bff → Dubbo RPC)</b>: 无版本前缀, 形如
 *       {@code /api/users}, {@code /api/courses}, {@code /api/orders}.</li>
 * </ul>
 *
 * 静态表 key 仅收录 System B 风格的无版本前缀 (如 {@code /api/users}), 历史上以
 * {@code path.startsWith(key)} 匹配。当 System A 的 {@code /api/v1/users} 进入 BFF 时,
 * 因为 {@code /api/v1/users} 不以 {@code /api/users} 开头, 会被误判为 unknown 路由。
 *
 * 本类提供 {@link #resolveService(String)}, 在匹配前将版本段
 * ({@code /api/v{n}/}) 归一化为 {@code /api/}, 使两套系统的请求都能命中同一条路由条目,
 * 同时保持 {@link #resolve(String)} 的向后兼容。
 */
public final class RouteTable {
    private static final Map<String, String> ROUTES = new LinkedHashMap<>();

    /**
     * 匹配 /api/v1/, /api/v2/ 等版本前缀段, 用于归一化。
     * 仅替换紧跟 /api/ 之后的单一版本段, 不影响后续路径中的版本号。
     */
    private static final Pattern VERSION_PREFIX = Pattern.compile("^/api/v\\d+/");

    static {
        // System B (portal-bff → Dubbo/HTTP) 与 System A (admin-web 直连) 共用同一条路由表.
        // key 统一采用无版本前缀形式, 由 resolveService 在匹配前归一化版本前缀。
        // value 为单元内 ClusterIP 服务短名:80 (与 deploy/k8s/base Service 名一致)。
        // D-068: 业务域 (users/courses/orders/learning/course) 落 core-business:80,
        //        IT 后端域 (assignment/grading) 落 grading-service:80。
        ROUTES.put("/api/live", "live-go:80");
        ROUTES.put("/api/ide", "ide-svc:80");
        ROUTES.put("/api/ws", "ws-gateway:80");
        ROUTES.put("/api/users", "core-business:80");
        ROUTES.put("/api/courses", "core-business:80");
        ROUTES.put("/api/orders", "core-business:80");
        ROUTES.put("/api/learning", "core-business:80");
        ROUTES.put("/api/course", "core-business:80");
        ROUTES.put("/api/assignments", "grading-service:80");
        ROUTES.put("/api/submissions", "grading-service:80");
        ROUTES.put("/api/grading", "grading-service:80");
        ROUTES.put("/api/ai", "ai-platform:80");
        // D-149: 康养养老事业群 → senior-care:80
        ROUTES.put("/api/elder", "senior-care:80");
        // D-149: 棋牌教育+对弈事业群 → boardgame-service:80
        ROUTES.put("/api/game", "boardgame-service:80");
    }

    private RouteTable() {}

    /**
     * 归一化请求路径: 剥离 /api 后的首个版本段 (System A 风格),
     * 使其落到 System B 风格的 key 空间。
     *
     * <pre>
     *   /api/v1/users/123    → /api/users/123
     *   /api/v2/courses/456  → /api/courses/456
     *   /api/users/123       → /api/users/123   (System B, 原样返回)
     *   /api/v1/learning      → /api/learning
     * </pre>
     *
     * @param requestPath 原始请求路径
     * @return 归一化后的路径 (始终以 /api/ 开头, 不含版本段)
     */
    static String normalize(String requestPath) {
        if (requestPath == null) {
            return null;
        }
        Matcher m = VERSION_PREFIX.matcher(requestPath);
        if (m.find()) {
            // 将 /api/v{n}/ 替换为 /api/, 保留后续路径片段.
            return "/api/" + requestPath.substring(m.end());
        }
        return requestPath;
    }

    /**
     * 解析请求路径对应的目标下游服务 (D-068 双路由系统调和入口).
     *
     * <p>步骤:
     * <ol>
     *   <li>归一化路径: {@code /api/v1/users/123} → {@code /api/users/123}。</li>
     *   <li>在静态路由表中查找最长前缀匹配 (避免 /api/learning 与 /api/learn 之类的歧义)。</li>
     *   <li>返回命中的目标服务名 (如 {@code core-business:80}); 未命中返回 {@code null}。</li>
     * </ol>
     *
     * <p>本方法同时接受 System A (带 /api/v{n}/) 与 System B (无版本前缀) 的请求,
     * 保证两侧路由语义一致。
     *
     * @param requestPath 原始请求路径
     * @return 目标服务 (host:port), 未匹配返回 null
     */
    public static String resolveService(String requestPath) {
        String normalized = normalize(requestPath);
        if (normalized == null) {
            return null;
        }
        String bestMatch = null;
        int bestLen = -1;
        for (var entry : ROUTES.entrySet()) {
            String key = entry.getKey();
            if (normalized.startsWith(key) && key.length() > bestLen) {
                bestMatch = key;
                bestLen = key.length();
            }
        }
        return bestMatch != null ? ROUTES.get(bestMatch) : null;
    }

    /**
     * 兼容旧入口: 等价于 {@link #resolveService(String)}, 保留以避免破坏既有调用方。
     *
     * @deprecated 使用 {@link #resolveService(String)} 获取更清晰的语义。
     * @param path 请求路径
     * @return 目标服务, 未匹配返回 null
     */
    // TODO D-120: Remove after migration complete. Production callers have
    // migrated to resolveService; the only remaining caller is RouteTableTest
    // (test-only). Once the test is updated to use resolveService, this method
    // and the test cases referencing it can be deleted.
    @Deprecated
    public static String resolve(String path) {
        return resolveService(path);
    }

    public static boolean isLocal(String path) {
        // 本地处理的路径同样要兼容 System A 的版本前缀 (/api/v1/auth/...).
        String normalized = normalize(path);
        return normalized.startsWith("/api/auth")
            || normalized.startsWith("/healthz")
            || normalized.startsWith("/actuator");
    }

    public static Map<String, String> getAll() {
        return Map.copyOf(ROUTES);
    }
}
