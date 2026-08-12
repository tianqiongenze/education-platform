package com.ep.bff.cellrouter;

/**
 * 路由结果 — sealed interface, 4 种可能结果.
 *
 * <p>D-040: GraalVM AOT 兼容 — sealed + record, 穷尽匹配.
 *
 * @see Pass
 * @see Redirect
 * @see Reject
 * @see Hold
 */
public sealed interface RouteResult permits RouteResult.Pass, RouteResult.Redirect, RouteResult.Reject, RouteResult.Hold {

    /**
     * 放行 — 请求到达正确 cell, 正常处理.
     *
     * @param cell 目标 cell
     */
    record Pass(CellInfo cell) implements RouteResult {}

    /**
     * 重路由 — 请求到达错误 cell, 需重定向到目标 cell.
     *
     * @param targetCell 目标 cell
     */
    record Redirect(CellInfo targetCell) implements RouteResult {}

    /**
     * 拒绝 — misroute 超限或其它不可恢复错误.
     *
     * @param statusCode HTTP 状态码 (如 403)
     * @param message    错误消息
     */
    record Reject(int statusCode, String message) implements RouteResult {}

    /**
     * 写挂起 — failover 期间写请求被挂起, 稍后重试.
     *
     * @param statusCode HTTP 状态码 (如 503)
     * @param message    错误消息
     */
    record Hold(int statusCode, String message) implements RouteResult {}
}
