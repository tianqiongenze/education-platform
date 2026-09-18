package com.ep.bff.dubbo;

import com.ep.business.user.CreateUserCmd;
import com.ep.business.user.UpdateUserCmd;
import com.ep.business.user.User;
import com.ep.business.user.UserService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * BFF 聚合控制器 — 用户域.
 * D-032: 通过 @DubboReference 调用 core-business 暴露的 UserService, 而非本地 HTTP.
 * 路由前缀 /api/users 走 BFF, 由 Dubbo 远程调用 core-business provider.
 *
 * D-070: Trace correlation — before each @DubboReference invocation, the MDC traceId
 * (populated by com.ep.bff.config.TraceIdFilter) must be propagated to the Dubbo RPC
 * context via com.ep.bff.config.TracePropagationUtil.propagateToRpc(), so downstream
 * core-business services can pick up the same traceId from RpcContext and bind it into their MDC.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserDubboController {

    @DubboReference(check = false, timeout = 10000)  // P2: 3000→5000, 匹配core-business provider超时
    private UserService userService;

    @PostMapping
    public ResponseEntity<User> create(@RequestBody CreateUserCmd cmd) {
        return ResponseEntity.ok(userService.createUser(cmd));
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
    public ResponseEntity<Void> update(@PathVariable Long id, @RequestBody UpdateUserCmd cmd) {
        Objects.requireNonNull(cmd, "body must not be null");
        cmd.setUserId(id);
        userService.updateUser(cmd);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/freeze")
    public ResponseEntity<Void> freeze(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        userService.freezeUser(id, reason);
        return ResponseEntity.noContent().build();
    }
}
