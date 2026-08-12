package com.ep.knowledge.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ep.memory.model.ContextWindow;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent conversation session.
 *
 * <p>D-045: the {@code context} field is now actively used to cache the
 * session-level {@link ContextWindow} summary so the orchestrator can avoid
 * a {@code MemoryManager.getContext()} round trip on every turn within the
 * same session.</p>
 *
 * <p>Three alternative caching strategies were considered:</p>
 * <ol>
 *   <li><b>Inline JSON column (chosen)</b> — {@code context} JSON column on
 *       {@code agent_session}; zero extra infra, fine for hot-session cache,
 *       MyBatis-Plus already maps it. Trade-off: row grows with summary size.</li>
 *   <li><b>Redis Cluster key per session</b> — lower DB load, TTL eviction
 *       for free, but adds a network hop and a second source of truth
 *       (铁律1 mandates Redis for *session state*, which we still honour
 *       for L0/L1 via the memory SDK; this column is a local mirror).</li>
 *   <li><b>In-process Caffeine cache</b> — fastest but not shareable across
 *       replicas; rejected for the same multi-instance reason.</li>
 * </ol>
 */
@TableName("agent_session")
public class AgentSession implements Serializable {

    private String id;
    private String agentId;
    private String tenantId;
    private String userId;

    /**
     * Conversation context cache.
     *
     * <p>Stores the session-scoped memory snapshot (working summary + last
     * seen turn id) to reduce MemoryManager call frequency. MyBatis-Plus maps
     * this to the {@code context} JSON column.</p>
     *
     * <p>Activated in D-045-WP5: the orchestrator writes a compact
     * {@link ContextWindow} view here after each turn and reads it back to
     * short-circuit {@code getContext()} on the next turn within the same
     * session.</p>
     */
    @TableField("context")
    private Map<String, Object> context;

    /**
     * Cached snapshot of the memory {@link ContextWindow} for this session.
     * Not persisted directly; serialized into {@link #context} as the
     * {@code memorySnapshot} key. Kept as a transient typed view so the
     * orchestrator does not re-deserialize on every turn.
     */
    private transient ContextWindow memorySnapshot;

    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
    public ContextWindow getMemorySnapshot() { return memorySnapshot; }
    public void setMemorySnapshot(ContextWindow memorySnapshot) {
        this.memorySnapshot = memorySnapshot;
        if (memorySnapshot != null) {
            if (this.context == null) {
                this.context = new LinkedHashMap<>();
            }
            this.context.put("memorySnapshot", memorySnapshot);
            this.context.put("workingSummary", memorySnapshot.workingSummary());
        }
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
