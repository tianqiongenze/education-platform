package com.ep.data.cdc;

import java.io.Serializable;
import java.util.Objects;

/**
 * User row image for the {@code user_group} CDC stream.
 */
public class UserCDC implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String phone;
    private Integer status;
    private Long tenantId;
    private Long createdAt;

    public UserCDC() {
    }

    public UserCDC(Long userId, String username, String phone, Integer status,
                   Long tenantId, Long createdAt) {
        this.userId = userId;
        this.username = username;
        this.phone = phone;
        this.status = status;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserCDC that)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(username, that.username)
                && Objects.equals(phone, that.phone)
                && Objects.equals(status, that.status)
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, username, phone, status, tenantId, createdAt);
    }

    @Override
    public String toString() {
        return "UserCDC{userId=" + userId + ", username='" + username + '\''
                + ", phone='" + phone + '\'' + ", status=" + status
                + ", tenantId=" + tenantId + ", createdAt=" + createdAt + '}';
    }
}
