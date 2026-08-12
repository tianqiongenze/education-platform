package com.ep.business.user;

import com.ep.business.RepositoryTestSupport;
import com.ep.business.common.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @DataJpaTest} slice tests for {@link UserRepository}. Covers the two
 * Spring-Data-derived unique-key lookups ({@code findByPhone} /
 * {@code findByUsername}) plus the base save path, against an in-memory H2
 * database in MySQL compatibility mode.
 */
@DataJpaTest
@Import(RepositoryTestSupport.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ep_user_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // D-132: keep the slice test off the network — Dubbo's default Nacos
        // registry lookup otherwise fails to load the ApplicationContext when no
        // Nacos server is reachable in CI/local dev.
        "dubbo.registry.address=N/A"
})
class UserRepositoryTest {

    @Autowired
    private UserRepository repository;

    private User newUser(String username, String phone) {
        User u = new User();
        // D-132: application-assigned Snowflake id (the entity no longer uses
        // @GeneratedValue IDENTITY — see User).
        u.setUserId(SnowflakeIdGenerator.getInstance().nextId());
        u.setUsername(username);
        u.setPhone(phone);
        u.setEmail(username + "@ep.com");
        u.setPasswordHash("hashed");
        u.setNickname(username);
        u.setStatus(UserStatus.ACTIVE.getCode());
        u.setTenantId(1L);
        u.setCreatedAt(System.currentTimeMillis());
        u.setUpdatedAt(System.currentTimeMillis());
        return u;
    }

    @Test
    void save_assignsIdAndPersists() {
        User saved = repository.save(newUser("alice", "13800000001"));

        assertNotNull(saved.getUserId());
        assertTrue(repository.findById(saved.getUserId()).isPresent());
    }

    @Test
    void findByPhone_found() {
        repository.save(newUser("alice", "13800000001"));

        Optional<User> found = repository.findByPhone("13800000001");

        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
    }

    @Test
    void findByPhone_notFound() {
        Optional<User> found = repository.findByPhone("13999999999");

        assertFalse(found.isPresent());
    }

    @Test
    void findByUsername_found() {
        repository.save(newUser("bob", "13800000002"));

        Optional<User> found = repository.findByUsername("bob");

        assertTrue(found.isPresent());
        assertEquals("13800000002", found.get().getPhone());
    }

    @Test
    void findByUsername_notFound() {
        Optional<User> found = repository.findByUsername("nobody");

        assertFalse(found.isPresent());
    }
}
