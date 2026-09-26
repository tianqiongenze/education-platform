package com.ep.business.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.ep.business.user.User;
import com.ep.business.user.UserRepository;
import com.ep.business.user.UserStatus;
import com.ep.web.common.ForbiddenException;
import com.ep.web.common.risk.LoginAttemptLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * D-227: {@link AuthService} — credential check integrated with the D-219
 * {@link LoginAttemptLimiter} (real in-memory limiter, mocked repository).
 *
 * <p>Lockout policy under test: 5 failures lock the principal; the next
 * {@code authenticate} attempt throws {@link AccountLockedException} (HTTP 423)
 * before any password work; a successful login resets the counter.</p>
 */
class AuthServiceTest {

    private static final int MAX_ATTEMPTS = 5;

    private UserRepository repository;
    private PasswordEncoder encoder;
    private LoginAttemptLimiter limiter;
    private AuthService service;

    private User activeUser;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        limiter = new LoginAttemptLimiter(MAX_ATTEMPTS, 15 * 60_000L, null); // real in-memory backend
        service = new AuthService(repository, encoder, limiter);

        activeUser = user(1L, "13800000000", encoder.encode("pw123"), UserStatus.ACTIVE.getCode());
        lenient().when(repository.findByPhone("13800000000")).thenReturn(Optional.of(activeUser));
    }

    private static User user(Long id, String phone, String hash, int status) {
        return new User(id, "user" + id, phone, phone + "@ep.com", hash,
                "Nick" + id, null, status, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("correct credentials return the user and clear the failure counter")
    void successfulLoginResetsCounter() {
        limiter.recordFailure("13800000000");
        limiter.recordFailure("13800000000");
        limiter.recordFailure("13800000000");

        User authenticated = service.authenticate("13800000000", "pw123");

        assertEquals(activeUser, authenticated);
        assertNotNull(authenticated.getPasswordHash());
        // Counter was reset by recordSuccess: 4 further failures must NOT lock.
        for (int i = 0; i < 4; i++) {
            try {
                service.authenticate("13800000000", "wrong");
            } catch (InvalidCredentialsException expected) {
                // ignore
            }
        }
        assertFalse(limiter.isLocked("13800000000"), "success must have reset the counter");
    }

    @Test
    @DisplayName("wrong password throws 401 semantics and records a failure")
    void wrongPasswordThrows401AndRecordsFailure() {
        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate("13800000000", "wrong"));
        assertTrue(ex.getMessage().contains("Invalid phone or password"));
        assertFalse(limiter.isLocked("13800000000"), "a single failure must not lock");
        // The recorded failure counts toward lockout:
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            limiter.recordFailure("13800000000");
        }
        assertTrue(limiter.isLocked("13800000000"), "1 + 4 failures must lock the principal");
    }

    @Test
    @DisplayName("5 failed logins lock the principal; authenticate then throws 423 semantics")
    void fiveFailuresThenLockout() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThrows(InvalidCredentialsException.class,
                    () -> service.authenticate("13800000000", "wrong-attempt"),
                    "attempt " + (i + 1) + " must fail with 401 semantics");
        }
        assertTrue(limiter.isLocked("13800000000"), "5 failures must lock the principal");

        // Even the CORRECT password is now rejected — with lockout semantics (423).
        AccountLockedException locked = assertThrows(AccountLockedException.class,
                () -> service.authenticate("13800000000", "pw123"));
        assertTrue(locked.getMessage().contains("locked"));
    }

    @Test
    @DisplayName("unknown phone fails with the same uniform 401 message (no enumeration) and records failure")
    void unknownPhoneIsUniform401() {
        when(repository.findByPhone("13999999999")).thenReturn(Optional.empty());

        InvalidCredentialsException known = assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate("13800000000", "wrong"));
        InvalidCredentialsException unknown = assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate("13999999999", "wrong"));
        assertEquals(known.getMessage(), unknown.getMessage(), "message must not reveal account existence");
        assertFalse(limiter.isLocked("13999999999"), "only 1 failure so far");
    }

    @Test
    @DisplayName("user without a password hash gets an explicit 409-semantics error")
    void passwordNotSetIsExplicit() {
        User hashless = user(2L, "13800000002", null, UserStatus.ACTIVE.getCode());
        when(repository.findByPhone("13800000002")).thenReturn(Optional.of(hashless));

        PasswordNotSetException ex = assertThrows(PasswordNotSetException.class,
                () -> service.authenticate("13800000002", "whatever"));
        assertTrue(ex.getMessage().contains("no password set"));
    }

    @Test
    @DisplayName("frozen and disabled users get 403 semantics after credential success")
    void inactiveUsersAreForbidden() {
        when(repository.findByPhone("13800000003"))
                .thenReturn(Optional.of(user(3L, "13800000003", encoder.encode("pw123"), UserStatus.FROZEN.getCode())));
        when(repository.findByPhone("13800000004"))
                .thenReturn(Optional.of(user(4L, "13800000004", encoder.encode("pw123"), UserStatus.DISABLED.getCode())));

        assertThrows(ForbiddenException.class, () -> service.authenticate("13800000003", "pw123"));
        assertThrows(ForbiddenException.class, () -> service.authenticate("13800000004", "pw123"));
    }

    @Test
    @DisplayName("blank password fails credential check without a repository hit")
    void blankPasswordRejected() {
        assertThrows(InvalidCredentialsException.class, () -> service.authenticate("13800000000", " "));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findByPhone(anyString());
    }
}
