package com.ep.business.user;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private UserRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        when(repository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getUserId() == null) {
                u.setUserId(System.nanoTime() % 100000L);
            }
            return u;
        });
        UserService userService = new UserServiceImpl(repository, encoderProvider(), noopPublisher());
        UserController controller = new UserController(userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** A disabled PulsarEventPublisher that never touches Pulsar. */
    @SuppressWarnings("unchecked")
    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private ObjectProvider<PasswordEncoder> encoderProvider() {
        return new ObjectProvider<>() {
            @Override public PasswordEncoder getObject() { return new BCryptPasswordEncoder(); }
            @Override public PasswordEncoder getIfAvailable() { return new BCryptPasswordEncoder(); }
            @Override public PasswordEncoder getIfUnique() { return new BCryptPasswordEncoder(); }
        };
    }

    @Test
    void createUser_endpoint() throws Exception {
        CreateUserCmd cmd = new CreateUserCmd("bob", "13800001111", "bob@ep.com", "pw", "Bobby");
        mockMvc.perform(post("/api/v1/users")
                        .header("X-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.phone").value("13800001111"))
                .andExpect(jsonPath("$.status").value(1));
    }

    @Test
    void getUserById_endpoint() throws Exception {
        CreateUserCmd cmd = new CreateUserCmd("carol", "13800002222", "carol@ep.com", "pw", "Cara");
        User created = new UserServiceImpl(repository, encoderProvider(), noopPublisher()).createUser(cmd);
        when(repository.findById(created.getUserId())).thenReturn(Optional.of(created));

        mockMvc.perform(get("/api/v1/users/{id}", created.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(created.getUserId().intValue()))
                .andExpect(jsonPath("$.username").value("carol"));
    }

    @Test
    void getUserById_notFound() throws Exception {
        when(repository.findById(123456L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/users/{id}", 123456L))
                .andExpect(status().isNotFound());
    }
}
