package com.ep.data.cdc;

/**
 * CDC operation type following Debezium/TiCDC convention.
 *
 * <p>D-028: TiCDC emits CREATE/UPDATE/DELETE events; READ is reserved for
 * snapshot/bootstrap reads.
 */
public enum OpType {

    CREATE("c"),
    UPDATE("u"),
    DELETE("d"),
    READ("r");

    private final String code;

    OpType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Resolve an {@link OpType} from its single-character TiCDC code.
     *
     * @param code one of "c","u","d","r" (case-insensitive)
     * @return the matching {@link OpType}
     * @throws IllegalArgumentException if the code is not recognized
     */
    public static OpType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("OpType code must not be null");
        }
        String normalized = code.trim().toLowerCase();
        for (OpType t : values()) {
            if (t.code.equals(normalized)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown OpType code: " + code);
    }
}
