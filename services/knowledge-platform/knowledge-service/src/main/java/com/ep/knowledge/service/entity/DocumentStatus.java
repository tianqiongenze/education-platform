package com.ep.knowledge.service.entity;

/**
 * D-201: Document lifecycle status.
 * <ul>
 *   <li>{@link #UPLOADED} — initial state after upload, awaiting parse-and-chunk.</li>
 *   <li>{@link #PARSED} — content has been parsed and chunked successfully.</li>
 *   <li>{@link #FAILED} — parsing failed (terminal).</li>
 * </ul>
 * The string code (name) is persisted in the {@code status} column for
 * backward compatibility with existing MyBatis-Plus rows.
 */
public enum DocumentStatus {
    UPLOADED,
    PARSED,
    FAILED;

    public String code() {
        return name();
    }

    public static DocumentStatus fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Document status value cannot be null");
        }
        try {
            return DocumentStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown document status: " + value);
        }
    }
}
