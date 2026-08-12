package com.ep.bff.cellrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 单元路由过滤器 — 在 AuthFilter 之后执行单元路由决策.
 *
 * <p>D-032: WebMVC OncePerRequestFilter (非 Spring Cloud Gateway GlobalFilter).
 * 与 AuthFilter 一致使用 OncePerRequestFilter 保证在 filter chain 内运行.
 *
 * <p>流程:
 * <ol>
 *   <li>仅拦截 /api/ 路径 (跳过 /healthz, /actuator, /.well-known, /api/auth/)</li>
 *   <li>从 X-User-Id 头获取 userId (AuthFilter 已注入)</li>
 *   <li>从 X-Cell-Name 头或 CELL_NAME 环境变量获取 currentCell</li>
 *   <li>currentCell 为空 → 跳过路由 (dev 模式或非单元部署)</li>
 *   <li>userId 为空 → 跳过路由 (未认证路径, 交给 AuthFilter 处理)</li>
 *   <li>调用 {@link CellRouter#route} 决策</li>
 *   <li>Pass → 设置 X-Target-Cell 响应头, 继续</li>
 *   <li>Redirect → 307 (保留方法) + Location 指向目标 cell</li>
 *   <li>Reject → 403 + JSON 错误体</li>
 *   <li>Hold → 503 + Retry-After: 30 + JSON 错误体</li>
 * </ol>
 *
 * <p>可通过 {@code ep.cell-router.enabled=false} 关闭.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "ep.cell-router.enabled", matchIfMissing = true)
public class CellRouterFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CellRouterFilter.class);

    private static final Set<String> WHITELIST = Set.of(
        "/api/auth/", "/api/v1/auth/", "/healthz", "/actuator/", "/.well-known/", "/error"
    );

    private final CellRouter cellRouter;
    private final ObjectMapper objectMapper;

    @Value("${ep.cell-router.cell-base-url-template:}")
    private String cellBaseUrlTemplate;

    @Value("${CELL_NAME:}")
    private String envCellName;

    public CellRouterFilter(CellRouter cellRouter, ObjectMapper objectMapper) {
        this.cellRouter = cellRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isWhitelisted(path) || !path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }

        String userId = req.getHeader("X-User-Id");
        String currentCell = resolveCurrentCell(req);

        // 非单元部署 (无 cell 头) 或未认证 → 跳过路由
        if (currentCell == null || currentCell.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        if (userId == null || userId.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }

        RouteResult result = cellRouter.route(userId, currentCell, req.getMethod());

        switch (result) {
            case RouteResult.Pass p -> {
                res.setHeader("X-Target-Cell", p.cell().cellName());
                chain.doFilter(req, res);
            }
            case RouteResult.Redirect r -> {
                String location = buildTargetLocation(r.targetCell(), req);
                res.setStatus(307); // Temporary Redirect, 保留 method+body
                res.setHeader("Location", location);
                res.setHeader("X-Target-Cell", r.targetCell().cellName());
                writeJsonError(res, 307, "redirect to correct cell " + r.targetCell().cellName());
            }
            case RouteResult.Reject rej -> {
                res.setStatus(rej.statusCode());
                writeJsonError(res, rej.statusCode(), rej.message());
            }
            case RouteResult.Hold h -> {
                res.setStatus(h.statusCode());
                res.setHeader("Retry-After", "30");
                writeJsonError(res, h.statusCode(), h.message());
            }
        }
    }

    /**
     * 解析当前 cell: 优先请求头 X-Cell-Name, 其次环境变量 CELL_NAME.
     */
    private String resolveCurrentCell(HttpServletRequest req) {
        String header = req.getHeader("X-Cell-Name");
        if (header != null && !header.isEmpty()) {
            return header;
        }
        return envCellName;
    }

    /**
     * 构造目标 cell 的 Location URL.
     *
     * <p>若配置了 cellBaseUrlTemplate (如 "https://{cell}.ep.example.com"), 则填充;
     * 否则仅返回相对路径 + X-Target-Cell 头由网关层处理.
     */
    private String buildTargetLocation(CellInfo targetCell, HttpServletRequest req) {
        if (cellBaseUrlTemplate != null && cellBaseUrlTemplate.contains("{cell}")) {
            String base = cellBaseUrlTemplate.replace("{cell}", targetCell.cellName());
            return base + req.getRequestURI()
                + (req.getQueryString() != null ? "?" + req.getQueryString() : "");
        }
        // 未配置模板: 返回相对路径, 依赖外部负载均衡按 X-Target-Cell 头路由
        return req.getRequestURI()
            + (req.getQueryString() != null ? "?" + req.getQueryString() : "");
    }

    private void writeJsonError(HttpServletResponse res, int status, String message) throws IOException {
        res.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", status == 403 ? "Forbidden" : "Service Unavailable");
        body.put("message", message);
        try {
            res.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (IOException e) {
            log.warn("CellRouterFilter: failed to write error body", e);
        }
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::startsWith);
    }
}
