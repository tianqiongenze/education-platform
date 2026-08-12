package com.ep.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-045 memory SDK configuration.
 *
 * <p>Bound from {@code ep.memory.*} properties.</p>
 */
@ConfigurationProperties(prefix = "ep.memory")
public class MemoryConfig {

    /** Master switch; when false no memory beans are created. */
    private boolean enabled = true;

    /** Dubbo RPC first; fall back to HTTP when false or RPC unavailable. */
    private boolean useRpc = true;

    /** SP-04 AI Gateway base URL used by the HTTP fallback client. */
    private String gatewayUrl = "http://ai-platform-gateway:8000";

    /** HTTP / RPC timeout in seconds. */
    private int timeoutSec = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isUseRpc() { return useRpc; }
    public void setUseRpc(boolean useRpc) { this.useRpc = useRpc; }

    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }

    public int getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(int timeoutSec) { this.timeoutSec = timeoutSec; }
}
