package com.ep.bff.dubbo;

import com.ep.api.dubbo.HealthRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dubbo 连通性探针 — 通过 @DubboReference 调用 ep-api 暴露的 HealthRpcService.
 * D-032: 使用 Dubbo 3.3.6 注解 (非 @Reference 旧注解).
 *
 * D-070: Trace correlation — like other BFF Dubbo controllers, the MDC traceId should
 * be propagated to the Dubbo RPC context via com.ep.bff.config.TracePropagationUtil
 * .propagateToRpc() before invoking healthRpcService for consistent cross-service tracing.
 */
@RestController
@RequestMapping("/api/v1/dubbo")
public class DubboHealthController {

    @DubboReference(check = false, timeout = 3000, url = "${dubbo.ref.health.url:}")
    private HealthRpcService healthRpcService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            var status = healthRpcService.check();
            if (status != null) {
                resp.put("healthy", status.isHealthy());
                resp.put("service", status.getService());
                resp.put("timestamp", status.getTimestamp());
            } else {
                resp.put("healthy", false);
                resp.put("error", "null status from provider");
            }
        } catch (Exception e) {
            // BFF consumer: check=false, provider 不在线时返回降级而非 500
            resp.put("healthy", false);
            resp.put("error", e.getMessage());
            resp.put("provider", "ep-api/HealthRpcService");
        }
        return resp;
    }
}
