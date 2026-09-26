package com.ep.business.auth;

import java.util.Objects;

import com.ep.business.user.User;
import com.ep.business.user.UserRepository;
import com.ep.business.user.UserStatus;
import com.ep.web.common.ForbiddenException;
import com.ep.web.common.risk.LoginAttemptLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * D-227: credential verification for the login loop.
 *
 * <p>Wires the D-219 {@link LoginAttemptLimiter} (previously a standalone
 * component with no login endpoint to protect) in front of the BCrypt
 * credential check:</p>
 * <ol>
 *   <li>locked principal → {@link AccountLockedException} (HTTP 423) before
 *       any password work (brake on credential stuffing)</li>
 *   <li>unknown phone / wrong password → {@link #limiter}.{@code recordFailure}
 *       + {@link InvalidCredentialsException} (HTTP 401, uniform message so
 *       callers cannot distinguish the two cases)</li>
 *   <li>user without a stored {@code passwordHash} →
 *       {@link PasswordNotSetException} (HTTP 409). Passwords are set via
 *       {@code UserServiceImpl.createUser} (BCrypt) and the dev seed
 *       {@code seedDevDefaults}; a dedicated password-set flow does not exist
 *       yet — see the javadoc on {@link PasswordNotSetException}.</li>
 *   <li>inactive user (DISABLED / FROZEN) → {@link ForbiddenException}
 *       (HTTP 403)</li>
 *   <li>success → {@code recordSuccess} clears the failure counter</li>
 * </ol>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter limiter;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       LoginAttemptLimiter limiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
    }

    /**
     * Verifies phone + password and applies the lockout policy.
     *
     * @return the authenticated (ACTIVE) user on success
     * @throws AccountLockedException       when the principal is locked out
     * @throws InvalidCredentialsException  on unknown phone or wrong password
     * @throws PasswordNotSetException      when the account has no password hash
     * @throws ForbiddenException           when the account is not ACTIVE
     */
    public User authenticate(String phone, String password) {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(password, "password must not be null");
        if (phone.isBlank() || password.isBlank()) {
            throw new InvalidCredentialsException("Invalid phone or password");
        }

        if (limiter.isLocked(phone)) {
            throw new AccountLockedException(
                    "Account temporarily locked after too many failed logins. Try again later.");
        }

        User user = userRepository.findByPhone(phone).orElse(null);
        if (user == null) {
            // Record failures for unknown principals too, so brute-forcing a
            // phone range cannot bypass the lockout.
            limiter.recordFailure(phone);
            throw new InvalidCredentialsException("Invalid phone or password");
        }

        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank()) {
            // No credential to check — do NOT record a failure (the user did
            // nothing wrong); surface a precise, actionable error instead.
            throw new PasswordNotSetException(
                    "This account has no password set; password login is unavailable until a password is created.");
        }

        if (!passwordEncoder.matches(password, hash)) {
            limiter.recordFailure(phone);
            throw new InvalidCredentialsException("Invalid phone or password");
        }

        if (user.getStatus() == null || user.getStatus() != UserStatus.ACTIVE.getCode()) {
            throw new ForbiddenException("User account is not active");
        }

        limiter.recordSuccess(phone);
        return user;
    }
}
