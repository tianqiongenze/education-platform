package com.ep.senior.care;

/**
 * D-150: Care plan status state machine.
 * <pre>
 *   DRAFT → ACTIVE → COMPLETED
 *                 ↘ ARCHIVED
 * </pre>
 */
public enum CarePlanStatus {
    DRAFT(0),
    ACTIVE(1),
    COMPLETED(2),
    ARCHIVED(3);

    private final int code;

    CarePlanStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static CarePlanStatus fromCode(int code) {
        for (CarePlanStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown CarePlanStatus code: " + code);
    }
}
