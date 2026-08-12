package com.ep.grading.controller;

import com.ep.web.common.RoleChecker;
import com.ep.grading.homework.dto.AssignmentCommand;
import com.ep.grading.homework.entity.Assignment;
import com.ep.grading.homework.service.AssignmentService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentController {

    @DubboReference
    private AssignmentService assignmentService;

    @PostMapping
    public ResponseEntity<Assignment> create(@RequestBody AssignmentCommand cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        Assignment created = assignmentService.create(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Assignment> publish(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        return ResponseEntity.ok(assignmentService.publish(id));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Assignment> close(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        return ResponseEntity.ok(assignmentService.close(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Assignment> findById(@PathVariable Long id) {
        Assignment assignment = assignmentService.findById(id);
        if (assignment == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(assignment);
    }

    @GetMapping
    public ResponseEntity<List<Assignment>> findByCourseId(@RequestParam("courseId") Long courseId) {
        return ResponseEntity.ok(assignmentService.findByCourseId(courseId));
    }
}
