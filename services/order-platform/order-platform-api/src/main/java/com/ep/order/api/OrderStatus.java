package com.ep.order.api;

/**
 * Order lifecycle states (SDD §3). Encoded in {@code ep_order.status} as the
 * {@link #getCode() int code} so the wire format is stable across refactors.
 */
public enum OrderStatus {
    PENDING(0),
    PAID(1),
    REFUNDED(2),
    CANCELED(3);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static OrderStatus fromCode(int code) {
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown OrderStatus code: " + code);
    }
}
