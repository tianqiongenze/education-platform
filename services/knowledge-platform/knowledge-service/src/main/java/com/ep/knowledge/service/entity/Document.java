package com.ep.knowledge.service.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ep.knowledge.service.event.DomainEvent;
import com.ep.knowledge.service.event.DocumentFailedEvent;
import com.ep.knowledge.service.event.DocumentParsedEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@TableName("kb_document")
public class Document {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @TableField("tenant_id")
    private String tenantId;

    private String title;

    private String format;

    @TableField("content_path")
    private String contentPath;

    @TableField("chunk_count")
    private Integer chunkCount;

    private String status;

    private Integer version;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * D-201: domain events recorded by aggregate behavior methods. Transient
     * so MyBatis-Plus ignores it; the application service pulls them after a
     * successful transaction via {@link #pullEvents()} and publishes to the
     * event bus.
     */
    private transient final List<DomainEvent> events = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getContentPath() { return contentPath; }
    public void setContentPath(String contentPath) { this.contentPath = contentPath; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Returns true iff the current status may transition to {@code newStatus}
     * (compared case-insensitively against {@link DocumentStatus} names).
     * <ul>
     *   <li>UPLOADED → PARSED</li>
     *   <li>UPLOADED → FAILED</li>
     * </ul>
     * Same-status is not a transition. PARSED and FAILED are terminal.
     */
    public boolean canTransitionTo(String newStatus) {
        if (newStatus == null) return false;
        String current = this.status == null ? null : this.status.toUpperCase();
        String next = newStatus.toUpperCase();
        if (next.equals(current)) return false;
        if (current == null) return false;
        return switch (current) {
            case "UPLOADED" -> "PARSED".equals(next) || "FAILED".equals(next);
            default -> false; // PARSED / FAILED / unknown are terminal
        };
    }

    /**
     * Transition UPLOADED -> PARSED. Sets the chunk count, records a
     * {@link DocumentParsedEvent}, and stamps {@code updatedAt}.
     *
     * @param chunkCount the number of chunks produced by parsing
     * @throws IllegalStateException if the document is not in UPLOADED status.
     */
    public void markParsed(int chunkCount) {
        if (status == null || !"UPLOADED".equalsIgnoreCase(status)) {
            throw new IllegalStateException(
                    "Document not in UPLOADED status, cannot mark parsed. documentId=" + id
                            + " status=" + status);
        }
        this.status = DocumentStatus.PARSED.code();
        this.chunkCount = chunkCount;
        this.updatedAt = LocalDateTime.now();
        recordEvent(new DocumentParsedEvent(
                id == null ? "" : id,
                id,
                tenantId,
                chunkCount,
                Instant.now()));
    }

    /**
     * Transition UPLOADED -> FAILED. Records a {@link DocumentFailedEvent}
     * and stamps {@code updatedAt}.
     *
     * @throws IllegalStateException if the document is not in UPLOADED status.
     */
    public void markFailed() {
        if (status == null || !"UPLOADED".equalsIgnoreCase(status)) {
            throw new IllegalStateException(
                    "Document not in UPLOADED status, cannot mark failed. documentId=" + id
                            + " status=" + status);
        }
        this.status = DocumentStatus.FAILED.code();
        this.updatedAt = LocalDateTime.now();
        recordEvent(new DocumentFailedEvent(
                id == null ? "" : id,
                id,
                tenantId,
                Instant.now()));
    }

    /**
     * Pull and clear recorded domain events. The application service calls
     * this after the transaction commits so the events can be published
     * (post-commit) to the event bus.
     */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> snapshot = new ArrayList<>(events);
        events.clear();
        return snapshot;
    }

    private void recordEvent(DomainEvent event) {
        events.add(event);
    }
}
