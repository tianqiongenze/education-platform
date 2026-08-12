package com.ep.web.common;

/**
 * Thrown when an authenticated user lacks the role required to perform an
 * action. Mapped to HTTP 403 by {@link GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
