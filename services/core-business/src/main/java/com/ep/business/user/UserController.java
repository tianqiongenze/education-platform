package com.ep.business.user;

import com.ep.web.common.RoleChecker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/users")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<User> create(@Valid @RequestBody CreateUserCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        return ResponseEntity.ok(userService.createUser(cmd));
    }

    @GetMapping
    public ResponseEntity<List<User>> list() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getById(@PathVariable Long id) {
        User user = userService.getUserById(id);
        return user == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(user);
    }

    @GetMapping("/phone/{phone}")
    public ResponseEntity<User> getByPhone(@PathVariable String phone) {
        User user = userService.getUserByPhone(phone);
        return user == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateUserCmd cmd,
                                       HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        Objects.requireNonNull(cmd, "body must not be null");
        cmd.setUserId(id);
        userService.updateUser(cmd);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/freeze")
    public ResponseEntity<Void> freeze(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        String reason = body == null ? null : body.get("reason");
        userService.freezeUser(id, reason);
        return ResponseEntity.noContent().build();
    }
}
