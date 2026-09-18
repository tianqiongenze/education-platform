package com.ep.bff.cellrouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 单元路由核心 — 根据 userId 计算目标 cell, 处理 misroute 纠正与 failover 写挂起.
 *
 * <p>设计见 user-service-sdd §4.1.1. 路由流程:
 * <ol>
 *   <li>hash 计算 target cell</li>
 *   <li>misroute 检测: 若 currentCell != target, 递增纠正计数; 超限则 Reject(403),
 *       否则 Redirect(target)</li>
 *   <li>到达正确 cell 后清除计数</li>
 *   <li>failover 写挂起: 写请求 + isHeld → Hold(503)</li>
 *   <li>否则 Pass(target)</li>
 * </ol>
 *
 * <p>D-039: 无 synchronized, 依赖下游组件的并发安全结构.
 * D-040: 纯 POJO Spring bean, GraalVM AOT 兼容.
 */
@Component
public class CellRouter {

    private static final Logger log = LoggerFactory.getLogger(CellRouter.class);

    private final RouteTable routeTable;
    private final RouteContext routeContext;
    private final WriteHoldRegistry writeHoldRegistry;

    @Value("${ep.cell-router.misroute-limit:3}")
    private int misrouteLimit;

    public CellRouter(RouteTable routeTable,
                      RouteContext routeContext,
                      WriteHoldRegistry writeHoldRegistry) {
        this.routeTable = routeTable;
        this.routeContext = routeContext;
        this.writeHoldRegistry = writeHoldRegistry;
    }

    /**
     * 路由决策.
     *
     * @param userId        用户 ID (可为 null, 回退到 cell 0)
     * @param currentCell   当前到达的 cell 名 (如 "cell-bj"), null 表示非单元部署
     * @param requestMethod HTTP 方法 (POST/PUT/PATCH/DELETE 视为写)
     * @return 路由结果 (Pass/Redirect/Reject/Hold)
     */
    public RouteResult route(String userId, String currentCell, String requestMethod) {
        // 1. 计算目标 cell
        int targetIndex = routeTable.hashRoute(userId);
        CellInfo target = routeTable.getCell(targetIndex);

        // 2. 单元部署下才做 misroute 检测; 非单元部署 (currentCell=null) 直接放行
        if (currentCell != null && !currentCell.isEmpty()
            && !target.cellName().equals(currentCell)) {
            int count = routeContext.incrementCorrectCount(userId);
            if (count > misrouteLimit) {
                log.warn("CellRouter: misroute limit exceeded for user {} (count={}, limit={})",
                    userId, count, misrouteLimit);
                return new RouteResult.Reject(403, "misroute limit exceeded for user " + userId);
            }
            log.debug("CellRouter: misroute user {} current={} target={} count={}",
                userId, currentCell, target.cellName(), count);
            return new RouteResult.Redirect(target);
        }

        // 3. 到达正确 cell — 清除纠正计数
        routeContext.clear(userId);

        // 4. failover 写挂起检查
        if (isWriteMethod(requestMethod) && writeHoldRegistry.isHeld(userId)) {
            log.info("CellRouter: write held for user {} during failover", userId);
            return new RouteResult.Hold(503, "write held during failover for user " + userId);
        }

        // 5. 放行
        return new RouteResult.Pass(target);
    }

    /**
     * 判断 HTTP 方法是否为写操作.
     *
     * @param method HTTP 方法 (大小写不敏感)
     * @return true 表示写 (POST/PUT/PATCH/DELETE)
     */
    public static boolean isWriteMethod(String method) {
        if (method == null || method.isEmpty()) {
            return false;
        }
        return switch (method.toUpperCase()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /**
     * 设置 misroute 限制 (测试用).
     *
     * @param limit 限制次数
     */
    public void setMisrouteLimit(int limit) {
        this.misrouteLimit = limit;
    }

    /** @return 当前 misroute 限制 */
    public int getMisrouteLimit() {
        return misrouteLimit;
    }
}
