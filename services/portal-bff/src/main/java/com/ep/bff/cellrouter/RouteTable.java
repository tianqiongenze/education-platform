package com.ep.bff.cellrouter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 单元路由表 — hash 路由 + Nacos 动态配置 (dev/CI 静态 fallback).
 *
 * <p>D-032: WebMVC HandlerInterceptor, 非 Spring Cloud Gateway.
 * D-039: volatile + 原子替换, 无 synchronized.
 * D-040: record + 纯 POJO, GraalVM AOT 兼容.
 *
 * <p>注意: 本类位于 {@code com.ep.bff.cellrouter} 包, 与 {@link com.ep.bff.edge.RouteTable}
 * (前缀路由表) 是两套不同的路由系统, 不要混淆.
 */
@Component
public class RouteTable {

    private static final Logger log = LoggerFactory.getLogger(RouteTable.class);

    /**
     * 静态 fallback 单元列表 (dev/CI, 无 Nacos).
     * cell-bj / cell-sz / cell-hz / cell-sg, 全部 ACTIVE.
     */
    private static final List<CellInfo> FALLBACK_CELLS = List.of(
        new CellInfo(0, "cell-bj", "cn-east-1", "bj", CellInfo.CellStatus.ACTIVE),
        new CellInfo(1, "cell-sz", "cn-south-1", "sz", CellInfo.CellStatus.ACTIVE),
        new CellInfo(2, "cell-hz", "cn-east-2", "hz", CellInfo.CellStatus.ACTIVE),
        new CellInfo(3, "cell-sg", "ap-southeast-1", "sg", CellInfo.CellStatus.ACTIVE)
    );

    /**
     * 路由表配置快照 — 原子替换的整体.
     *
     * @param cells     当前生效的单元列表
     * @param version   配置版本号
     * @param updatedAt 更新时间戳 (epoch millis)
     */
    public record CellConfigSnapshot(List<CellInfo> cells, int version, long updatedAt) {}

    /** volatile 保证多线程可见性, 引用替换即原子切换. */
    private volatile CellConfigSnapshot snapshot;

    @Value("${EP_DEV_ONLY:false}")
    private String epDevOnly;

    @Value("${ep.cell-router.cells:bj,sz,hz,sg}")
    private String configuredCells;

    @PostConstruct
    void init() {
        // dev/CI: 直接使用静态 fallback (Iron Law 0: 不连 Nacos)
        if (isDevOnly()) {
            log.info("RouteTable init: EP_DEV_ONLY=true, using static fallback cells (no Nacos)");
            this.snapshot = new CellConfigSnapshot(FALLBACK_CELLS, 1, System.currentTimeMillis());
            return;
        }
        // 生产: 同样从静态 fallback 起步, 待 Nacos 监听器回调 onConfigChange 更新
        log.info("RouteTable init: starting with fallback cells, awaiting Nacos config update");
        this.snapshot = new CellConfigSnapshot(FALLBACK_CELLS, 1, System.currentTimeMillis());
    }

    /**
     * 计算 userId 的目标单元索引.
     *
     * <p>空/null userId → 确定性回退到 cell 0.
     * 使用 userId.hashCode() (MVP, 足够均匀; 后续可换 Murmur3).
     *
     * @param userId 用户 ID
     * @return 目标单元索引 (0-based)
     */
    public int hashRoute(String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        int cellCount = getCellCount();
        if (cellCount <= 0) {
            return 0;
        }
        // Math.abs(Integer.MIN_VALUE) 仍为负, 用位运算兜底
        int hash = userId.hashCode();
        int positive = hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
        return positive % cellCount;
    }

    /**
     * 获取指定索引的单元信息.
     *
     * @param index 单元索引 (0-based)
     * @return 单元信息, 越界返回 fallback cell 0
     */
    public CellInfo getCell(int index) {
        List<CellInfo> cells = snapshot.cells();
        if (cells.isEmpty()) {
            return FALLBACK_CELLS.get(0);
        }
        if (index < 0 || index >= cells.size()) {
            return cells.get(0);
        }
        return cells.get(index);
    }

    /** @return 当前生效单元总数 */
    public int getCellCount() {
        return snapshot.cells().size();
    }

    /**
     * 按单元名查找.
     *
     * @param name 单元名, 如 "cell-bj"
     * @return 匹配的单元信息, 未找到返回 empty
     */
    public Optional<CellInfo> getCellByName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return snapshot.cells().stream()
            .filter(c -> name.equals(c.cellName()))
            .findFirst();
    }

    /**
     * 获取当前快照 (供监控/测试).
     *
     * @return 当前配置快照
     */
    public CellConfigSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Nacos 配置变更回调 — 解析新配置并原子替换快照.
     *
     * <p>EP_DEV_ONLY (Iron Law 0): dev/CI 禁止连 Nacos, 此方法 no-op 仅记录日志.
     *
     * @param newConfig Nacos 下发的配置字符串 (逗号分隔 cell zone, 或 JSON)
     */
    public void onConfigChange(String newConfig) {
        if (isDevOnly()) {
            // Iron Law 0: dev/CI 禁止连接真实 Nacos/Redis
            log.warn("RouteTable.onConfigChange: EP_DEV_ONLY=true, ignoring Nacos config update (Iron Law 0)");
            return;
        }
        if (newConfig == null || newConfig.isBlank()) {
            log.warn("RouteTable.onConfigChange: empty config, keeping current snapshot");
            return;
        }
        try {
            List<CellInfo> newCells = parseConfig(newConfig);
            if (newCells.isEmpty()) {
                log.warn("RouteTable.onConfigChange: parsed 0 cells, keeping current snapshot");
                return;
            }
            int newVersion = snapshot.version() + 1;
            CellConfigSnapshot newSnapshot = new CellConfigSnapshot(newCells, newVersion, System.currentTimeMillis());
            // volatile 写: 原子替换, 其它线程立即可见新引用
            this.snapshot = newSnapshot;
            log.info("RouteTable updated: version={}, cells={}", newVersion,
                newCells.stream().map(CellInfo::cellName).toList());
        } catch (Exception e) {
            log.error("RouteTable.onConfigChange: parse failed, keeping current snapshot", e);
        }
    }

    /**
     * 解析 Nacos 配置字符串为单元列表.
     *
     * <p>支持两种格式:
     * <ul>
     *   <li>逗号分隔 zone: "bj,sz,hz,sg"</li>
     *   <li>完整 cell 名: "cell-bj,cell-sz"</li>
     * </ul>
     *
     * @param config 配置字符串
     * @return 单元列表
     */
    private List<CellInfo> parseConfig(String config) {
        String[] parts = config.split(",");
        List<CellInfo> result = new java.util.ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            String zone;
            String cellName;
            if (part.startsWith("cell-")) {
                cellName = part;
                zone = part.substring("cell-".length());
            } else {
                zone = part;
                cellName = "cell-" + part;
            }
            String region = guessRegion(zone);
            result.add(new CellInfo(i, cellName, region, zone, CellInfo.CellStatus.ACTIVE));
        }
        return List.copyOf(result);
    }

    /** 根据 zone 猜测 region (MVP 简化). */
    private String guessRegion(String zone) {
        return switch (zone) {
            case "bj" -> "cn-east-1";
            case "sz" -> "cn-south-1";
            case "hz" -> "cn-east-2";
            case "sg" -> "ap-southeast-1";
            default -> "unknown-" + zone;
        };
    }

    private boolean isDevOnly() {
        return "true".equalsIgnoreCase(epDevOnly) || "1".equals(epDevOnly);
    }
}
