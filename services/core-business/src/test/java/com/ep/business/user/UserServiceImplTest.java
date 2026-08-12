package com.ep.business.user;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceImplTest {

    private UserRepository repository;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        // Simulate JPA auto-assigning an ID on save.
        when(repository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getUserId() == null) {
                u.setUserId(System.nanoTime() % 100000L);
            }
            return u;
        });
        service = new UserServiceImpl(repository, passwordEncoderProvider(), noopPublisher());
    }

    /** A disabled PulsarEventPublisher that never touches Pulsar. */
    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private ObjectProvider<org.springframework.security.crypto.password.PasswordEncoder> passwordEncoderProvider() {
        return new ObjectProvider<>() {
            @Override
            public org.springframework.security.crypto.password.PasswordEncoder getObject() {
                return new BCryptPasswordEncoder();
            }
            @Override
            public org.springframework.security.crypto.password.PasswordEncoder getIfAvailable() {
                return new BCryptPasswordEncoder();
            }
            @Override
            public org.springframework.security.crypto.password.PasswordEncoder getIfUnique() {
                return new BCryptPasswordEncoder();
            }
        };
    }

    private CreateUserCmd cmd(String phone) {
        return new CreateUserCmd("alice", phone, "alice@ep.com", "secret", "Ali");
    }

    @Test
    void createUser_success() {
        User user = service.createUser(cmd("13800000001"));
        assertNotNull(user.getUserId());
        assertEquals("alice", user.getUsername());
        assertEquals("13800000001", user.getPhone());
        assertEquals(UserStatus.ACTIVE.getCode(), user.getStatus());
        assertNotNull(user.getPasswordHash());
        assertNotEquals("secret", user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2a$"), "password must be BCrypt-encoded");
        assertNotNull(user.getCreatedAt());
    }

    @Test
    void createUser_duplicatePhoneRejected() {
        when(repository.findByPhone("13800000002")).thenReturn(Optional.of(new User()));
        assertThrows(IllegalStateException.class, () -> service.createUser(cmd("13800000002")));
    }

    @Test
    void createUser_blankPhoneRejected() {
        CreateUserCmd c = cmd(" ");
        assertThrows(IllegalArgumentException.class, () -> service.createUser(c));
    }

    @Test
    void getUserById_found() {
        User created = service.createUser(cmd("13800000003"));
        when(repository.findById(created.getUserId())).thenReturn(Optional.of(created));
        User found = service.getUserById(created.getUserId());
        assertNotNull(found);
        assertEquals(created.getUserId(), found.getUserId());
    }

    @Test
    void getUserById_notFound() {
        when(repository.findById(99999L)).thenReturn(Optional.empty());
        assertNull(service.getUserById(99999L));
    }

    @Test
    void getUserByPhone_found() {
        User created = service.createUser(cmd("13800000004"));
        when(repository.findByPhone("13800000004")).thenReturn(Optional.of(created));
        User found = service.getUserByPhone("13800000004");
        assertNotNull(found);
        assertEquals("13800000004", found.getPhone());
    }

    @Test
    void getUserByPhone_notFound() {
        when(repository.findByPhone("13900000000")).thenReturn(Optional.empty());
        assertNull(service.getUserByPhone("13900000000"));
    }

    @Test
    void freezeUser_setsFrozenStatus() {
        User u = service.createUser(cmd("13800000005"));
        when(repository.findById(u.getUserId())).thenReturn(Optional.of(u));
        service.freezeUser(u.getUserId(), "abuse");
        assertEquals(UserStatus.FROZEN.getCode(), u.getStatus());
        assertTrue(u.getUpdatedAt() >= u.getCreatedAt());
    }

    @Test
    void freezeUser_notFoundThrows() {
        when(repository.findById(88888L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.freezeUser(88888L, "x"));
    }

    @Test
    void updateUser_updatesFields() {
        User u = service.createUser(cmd("13800000006"));
        when(repository.findById(u.getUserId())).thenReturn(Optional.of(u));
        UpdateUserCmd upd = new UpdateUserCmd(u.getUserId(), "NewNick", "http://avatar.png", "new@ep.com");
        service.updateUser(upd);
        assertEquals("NewNick", u.getNickname());
        assertEquals("http://avatar.png", u.getAvatar());
        assertEquals("new@ep.com", u.getEmail());
    }

    @Test
    void updateUser_notFoundThrows() {
        when(repository.findById(77777L)).thenReturn(Optional.empty());
        UpdateUserCmd upd = new UpdateUserCmd(77777L, "n", "a", "e");
        assertThrows(IllegalStateException.class, () -> service.updateUser(upd));
    }

    @Test
    void bcryptPasswordEncoder_verifiesPassword() {
        User u = service.createUser(cmd("13800000007"));
        BCryptPasswordEncoder verifier = new BCryptPasswordEncoder();
        assertTrue(verifier.matches("secret", u.getPasswordHash()));
    }
}
