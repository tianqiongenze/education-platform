package com.ep.bff.config;

import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.MDC;

/** D-070: Propagates MDC traceId to Dubbo RPC context for cross-service tracing. */
public final class TracePropagationUtil {
    private TracePropagationUtil() {}

    public static void propagateToRpc() {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            RpcContext.getClientAttachment().setAttachment("traceId", traceId);
        }
    }
}
