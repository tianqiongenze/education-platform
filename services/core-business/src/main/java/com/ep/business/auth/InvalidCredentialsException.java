package com.ep.business.auth;

/**
 * D-227: authentication failure (unknown phone or wrong password).
 * Mapped to HTTP 401 by {@code AuthExceptionHandler}. Deliberately carries a
 * uniform message for both cases to prevent account enumeration.
 */
public class InvalidCredentialsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
