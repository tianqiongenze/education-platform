package com.ep.bff.dubbo;

import com.ep.business.user.CreateUserCmd;
import com.ep.business.user.UpdateUserCmd;
import com.ep.business.user.User;
import com.ep.business.user.UserStatus;
import com.ep.business.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link UserDubboController}. The
 * {@code @DubboReference} field is injected via {@link ReflectionTestUtils}.
 */
class UserDubboControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        UserDubboController controller = new UserDubboController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private User userWith(Long id, String username) {
        return new User(id, username, "13800000001", "u@ep.com",
                "hash", "nick", null, UserStatus.ACTIVE.getCode(),
                1L, System.currentTimeMillis(), System.currentTimeMillis());
    }

    @Test
    void create_endpoint() throws Exception {
        when(userService.createUser(any())).thenReturn(userWith(1L, "alice"));

        var cmd = new CreateUserCmd("alice", "13800000001", "u@ep.com", "pass", "nick");
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void getById_endpoint() throws Exception {
        when(userService.getUserById(1L)).thenReturn(userWith(1L, "alice"));

        mockMvc.perform(get("/api/v1/users/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1));
    }

    @Test
    void getById_notFound() throws Exception {
        when(userService.getUserById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/users/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByPhone_endpoint() throws Exception {
        when(userService.getUserByPhone("13800000001")).thenReturn(userWith(1L, "alice"));

        mockMvc.perform(get("/api/v1/users/phone/{phone}", "13800000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void getByPhone_notFound() throws Exception {
        when(userService.getUserByPhone(any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/users/phone/{phone}", "13999999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_endpoint() throws Exception {
        var cmd = new UpdateUserCmd(null, "newnick", "avatar-url", "new@ep.com");
        mockMvc.perform(put("/api/v1/users/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isNoContent());

        verify(userService).updateUser(any(UpdateUserCmd.class));
    }

    @Test
    void freeze_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/users/{id}/freeze", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("reason", "violation"))))
                .andExpect(status().isNoContent());

        verify(userService).freezeUser(1L, "violation");
    }
}
