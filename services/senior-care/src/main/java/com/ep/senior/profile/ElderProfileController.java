package com.ep.senior.profile;

import com.ep.web.common.RoleChecker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/elders")
@Validated
public class ElderProfileController {

    private final ElderProfileService elderService;

    public ElderProfileController(ElderProfileService elderService) {
        this.elderService = elderService;
    }

    @PostMapping
    public ResponseEntity<ElderProfile> create(@Valid @RequestBody CreateElderCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver");
        return ResponseEntity.ok(elderService.createElder(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ElderProfile> getById(@PathVariable Long id) {
        ElderProfile elder = elderService.getElderById(id);
        return elder == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(elder);
    }

    @GetMapping
    public ResponseEntity<List<ElderProfile>> list(@RequestParam(defaultValue = "1") Integer page,
                                                    @RequestParam(defaultValue = "10") Integer size) {
        return ResponseEntity.ok(elderService.listElders(page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<List<ElderProfile>> search(@RequestParam String keyword) {
        return ResponseEntity.ok(elderService.searchElders(keyword));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver");
        elderService.pauseElder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver");
        elderService.activateElder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/discharge")
    public ResponseEntity<Void> discharge(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        elderService.dischargeElder(id);
        return ResponseEntity.noContent().build();
    }
}
