package com.ep.grading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * D-186: WebContainer configuration.
 * <p>
 * WebContainer runs entirely client-side (WASM); the backend only coordinates
 * the session and persists the Yjs document. This configuration controls the
 * few server-side knobs that affect the collaboration layer: the feature flag,
 * the memory budget advertised to clients, and the session timeout.
 * <p>
 * <b>EP_DEV_ONLY guard:</b> the feature is disabled by default and only enabled
 * when {@code webcontainer.enabled=true}. This prevents the IDE WebSocket
 * handler from accepting connections during local development unless explicitly
 * opted in.
 */
@Configuration
@ConfigurationProperties(prefix = "webcontainer")
public class WebContainerConfig {

    /** Feature flag — disabled by default. */
    private boolean enabled = false;

    /** Max memory (MB) advertised to the WebContainer client. */
    private int maxMemoryMB = 256;

    /** Default execution timeout (seconds) for student code in the container. */
    private int timeoutSec = 30;

    /** Maximum concurrent collaborators per session. */
    private int maxCollaborators = 16;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxMemoryMB() {
        return maxMemoryMB;
    }

    public void setMaxMemoryMB(int maxMemoryMB) {
        this.maxMemoryMB = maxMemoryMB;
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(int timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    public int getMaxCollaborators() {
        return maxCollaborators;
    }

    public void setMaxCollaborators(int maxCollaborators) {
        this.maxCollaborators = maxCollaborators;
    }
}
