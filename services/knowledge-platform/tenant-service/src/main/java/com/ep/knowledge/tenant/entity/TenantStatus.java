package com.ep.knowledge.tenant.entity;

/**
 * D-201: Tenant lifecycle status.
 * <ul>
 *   <li>{@link #PENDING} — initial state after creation, awaiting activation.</li>
 *   <li>{@link #ACTIVE} — tenant is operational.</li>
 *   <li>{@link #SUSPENDED} — tenant temporarily disabled (can be reactivated).</li>
 * </ul>
 * The string code (name) is persisted in the {@code status} column.
 */
public enum TenantStatus {
    PENDING,
    ACTIVE,
    SUSPENDED;

    public String code() {
        return name();
    }

    public static TenantStatus fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Tenant status value cannot be null");
        }
        try {
            return TenantStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown tenant status: " + value);
        }
    }
}
