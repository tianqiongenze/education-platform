package com.ep.business.user;

import com.ep.business.common.SnowflakeIdGenerator;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import jakarta.annotation.PostConstruct;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@DubboService
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    /** D-182: cache names and TTLs for the user domain. */
    private static final String CACHE_USER = "user";
    private static final String CACHE_USER_LIST = "user:list";
    private static final String LIST_ALL_KEY = "all";
    private static final long USER_TTL_SEC = 600;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PulsarEventPublisher eventPublisher;
    private final CacheService cacheService;

    public UserServiceImpl(UserRepository userRepository,
                           ObjectProvider<PasswordEncoder> passwordEncoderProvider,
                           PulsarEventPublisher eventPublisher,
                           CacheService cacheService) {
        this.userRepository = userRepository;
        PasswordEncoder encoder = passwordEncoderProvider.getIfAvailable();
        this.passwordEncoder = encoder != null ? encoder : new BCryptPasswordEncoder();
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
    }

    /**
     * D-048/P0-5: dev-only seed. Resets default demo user with a BCrypt password
     * so the platform boots with a known-good credential. Only runs when the
     * ep.dev.seed-enabled property is true (defaulted on for dev profile).
     */
    @PostConstruct
    public void seedDevDefaults() {
        if (!Boolean.getBoolean("ep.dev.seed-enabled")) {
            return;
        }
        try {
            if (userRepository.findByPhone("13800000000").isEmpty()) {
                long now = System.currentTimeMillis();
                User demo = new User();
                demo.setUserId(SnowflakeIdGenerator.getInstance().nextId());
                demo.setUsername("demo");
                demo.setPhone("13800000000");
                demo.setEmail("demo@ep.com");
                demo.setPasswordHash(passwordEncoder.encode("demo123"));
                demo.setNickname("Demo");
                demo.setStatus(UserStatus.ACTIVE.getCode());
                demo.setTenantId(0L);
                demo.setCreatedAt(now);
                demo.setUpdatedAt(now);
                userRepository.save(demo);
                log.info("Seeded dev demo user (phone=13800000000)");
            }
        } catch (Exception e) {
            log.warn("Dev seed skipped: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public User createUser(CreateUserCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        requireNonBlank(cmd.getUsername(), "username");
        requireNonBlank(cmd.getPhone(), "phone");
        requireNonBlank(cmd.getPassword(), "password");

        if (userRepository.findByPhone(cmd.getPhone()).isPresent()) {
            throw new IllegalStateException("Duplicate phone: " + cmd.getPhone());
        }
        if (userRepository.findByUsername(cmd.getUsername()).isPresent()) {
            throw new IllegalStateException("Duplicate username: " + cmd.getUsername());
        }

        long now = System.currentTimeMillis();
        User user = new User();
        user.setUserId(SnowflakeIdGenerator.getInstance().nextId());
        user.setUsername(cmd.getUsername());
        user.setPhone(cmd.getPhone());
        user.setEmail(cmd.getEmail());
        user.setPasswordHash(passwordEncoder.encode(cmd.getPassword()));
        user.setNickname(cmd.getNickname() == null ? cmd.getUsername() : cmd.getNickname());
        user.setAvatar(null);
        user.setStatus(UserStatus.ACTIVE.getCode());
        user.setTenantId(0L);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        User saved = userRepository.save(user);
        // D-182: evict list cache after user creation.
        cacheService.evict(CACHE_USER_LIST, LIST_ALL_KEY);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public User getUserById(Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return cacheService.getOrLoad(CACHE_USER, String.valueOf(userId),
                () -> userRepository.findById(userId).orElse(null), USER_TTL_SEC, true);
    }

    @Override
    @Transactional(readOnly = true)
    public User getUserByPhone(String phone) {
        requireNonBlank(phone, "phone");
        return cacheService.getOrLoad(CACHE_USER, "phone:" + phone,
                () -> userRepository.findByPhone(phone).orElse(null), USER_TTL_SEC, true);
    }

    /**
     * D-065: Returns all users. Backs the {@code GET /api/v1/users} endpoint
     * that the admin-web frontend relies on (via {@code listUsers()} →
     * {@code listAllOrders()} fan-out). Uses a simple findAll for now; the
     * admin tool's expected volume is low. If volumes grow, switch to
     * pagination (page/size) and push the fan-out to the backend.
     */
    @Override
    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return cacheService.getOrLoad(CACHE_USER_LIST, LIST_ALL_KEY,
                () -> userRepository.findAll(), USER_TTL_SEC, false);
    }

    @Override
    @Transactional
    public void updateUser(UpdateUserCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(cmd.getUserId(), "userId must not be null");
        User user = userRepository.findById(cmd.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found: " + cmd.getUserId()));
        if (cmd.getNickname() != null) user.setNickname(cmd.getNickname());
        if (cmd.getAvatar() != null) user.setAvatar(cmd.getAvatar());
        if (cmd.getEmail() != null) user.setEmail(cmd.getEmail());
        user.setUpdatedAt(System.currentTimeMillis());
        userRepository.save(user);
        // D-182: evict cached user entries after an update.
        cacheService.evict(CACHE_USER, String.valueOf(cmd.getUserId()));
        cacheService.evict(CACHE_USER, "phone:" + user.getPhone());
        cacheService.evict(CACHE_USER_LIST, LIST_ALL_KEY);
    }

    @Override
    @Transactional
    public void freezeUser(Long userId, String reason) {
        Objects.requireNonNull(userId, "userId must not be null");
        requireNonBlank(reason, "reason");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        // D-066: state transition moved into the User aggregate.
        user.freeze(reason);
        userRepository.save(user);
        // D-066: pull and surface domain events, publish via Pulsar (best-effort).
        for (com.ep.business.common.DomainEvent event : user.pullEvents()) {
            log.info("D-066: domain event recorded: {}", event);
            eventPublisher.publish(event);
        }
        // D-182: evict cached user entries after a state change.
        cacheService.evict(CACHE_USER, String.valueOf(userId));
        cacheService.evict(CACHE_USER, "phone:" + user.getPhone());
        cacheService.evict(CACHE_USER_LIST, LIST_ALL_KEY);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
