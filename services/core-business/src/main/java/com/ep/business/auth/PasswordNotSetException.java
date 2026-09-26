package com.ep.business.auth;

/**
 * D-227: the user record has no usable {@code passwordHash}, so password login
 * is impossible for this account. Passwords are set today via user creation
 * ({@code UserServiceImpl.createUser} BCrypt-encodes {@code CreateUserCmd.password})
 * and the dev seed ({@code seedDevDefaults}); accounts created through other
 * paths may lack one. Mapped to HTTP 409 (CONFLICT) by {@code AuthExceptionHandler}.
 */
public class PasswordNotSetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PasswordNotSetException(String message) {
        super(message);
    }
}
