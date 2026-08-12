package com.ep.senior.profile;

/**
 * D-148: Elder profile status state machine.
 * <pre>
 *   ACTIVE  ←→ PAUSED  → DISCHARGED
 *   (active care) (temp hold) (left facility)
 * </pre>
 */
public enum ElderStatus {
    ACTIVE(1),
    PAUSED(2),
    DISCHARGED(3);

    private final int code;

    ElderStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static ElderStatus fromCode(int code) {
        for (ElderStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown ElderStatus code: " + code);
    }
}
