package com.ep.business.course;

public enum CourseStatus {
    DRAFT(0),
    PUBLISHED(1),
    ARCHIVED(2);

    private final int code;

    CourseStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static CourseStatus fromCode(int code) {
        for (CourseStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown CourseStatus code: " + code);
    }
}
