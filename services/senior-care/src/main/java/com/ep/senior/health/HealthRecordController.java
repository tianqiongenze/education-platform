package com.ep.senior.health;

import com.ep.web.common.RoleChecker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/health-records")
public class HealthRecordController {

    private final HealthRecordService healthService;

    public HealthRecordController(HealthRecordService healthService) {
        this.healthService = healthService;
    }

    @PostMapping
    public ResponseEntity<HealthRecord> create(@RequestBody CreateHealthRecordCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver", "nurse");
        return ResponseEntity.ok(healthService.createRecord(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HealthRecord> getById(@PathVariable Long id) {
        HealthRecord record = healthService.getRecordById(id);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(record);
    }

    @GetMapping(params = "elderId")
    public ResponseEntity<List<HealthRecord>> listByElder(@RequestParam Long elderId) {
        return ResponseEntity.ok(healthService.listByElder(elderId));
    }

    @GetMapping("/abnormal")
    public ResponseEntity<List<HealthRecord>> listAbnormal(@RequestParam Long elderId) {
        return ResponseEntity.ok(healthService.listAbnormalByElder(elderId));
    }
}
