package com.ep.senior.care;

import com.ep.web.common.RoleChecker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/care-plans")
@Validated
public class CarePlanController {

    private final CarePlanService carePlanService;

    public CarePlanController(CarePlanService carePlanService) {
        this.carePlanService = carePlanService;
    }

    @PostMapping
    public ResponseEntity<CarePlan> create(@Valid @RequestBody CreateCarePlanCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver");
        return ResponseEntity.ok(carePlanService.createPlan(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CarePlan> getById(@PathVariable Long id) {
        CarePlan plan = carePlanService.getPlanById(id);
        return plan == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(plan);
    }

    @GetMapping(params = "elderId")
    public ResponseEntity<List<CarePlan>> listByElder(@RequestParam Long elderId) {
        return ResponseEntity.ok(carePlanService.listByElder(elderId));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver");
        carePlanService.activatePlan(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "caregiver");
        carePlanService.completePlan(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        carePlanService.archivePlan(id);
        return ResponseEntity.noContent().build();
    }
}
