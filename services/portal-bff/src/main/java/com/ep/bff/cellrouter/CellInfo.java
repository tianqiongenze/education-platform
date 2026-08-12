package com.ep.bff.cellrouter;

/**
 * 单元元数据 — 多活部署中一个 cell 的描述信息.
 *
 * <p>D-041: 多活部署 4 单元 (cell-bj, cell-sz, cell-hz, cell-sg).
 * D-040: GraalVM AOT 兼容 — 纯 record, 无反射依赖.
 *
 * @param cellId  0-based 单元索引
 * @param cellName 单元名, 如 "cell-bj"
 * @param region  云区域, 如 "cn-east-1"
 * @param zone    可用区简称, 如 "bj"
 * @param status  单元状态 (ACTIVE/DRAINING/FAILOVER)
 */
public record CellInfo(
    int cellId,
    String cellName,
    String region,
    String zone,
    CellStatus status
) {
    /**
     * 单元状态枚举.
     *
     * <ul>
     *   <li>{@link #ACTIVE} — 正常承接流量</li>
     *   <li>{@link #DRAINING} — 排水中, 仅接受已路由请求</li>
     *   <li>{@link #FAILOVER} — 故障转移中, 写挂起</li>
     * </ul>
     */
    public enum CellStatus {
        ACTIVE,
        DRAINING,
        FAILOVER
    }
}
