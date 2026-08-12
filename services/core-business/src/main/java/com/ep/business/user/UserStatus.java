package com.ep.business.user;

public enum UserStatus {
    DISABLED(0),
    ACTIVE(1),
    FROZEN(2);

    private final int code;

    UserStatus(int code) { this.code = code; }

    public int getCode() { return code; }

    public static UserStatus fromCode(int code) {
        for (UserStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown UserStatus code: " + code);
    }
}
