package com.ep.grading.controller;

import com.ep.web.common.RoleChecker;
import com.ep.grading.homework.dto.GradeSubmissionCommand;
import com.ep.grading.homework.dto.SubmissionCommand;
import com.ep.grading.homework.entity.Submission;
import com.ep.grading.homework.service.SubmissionService;
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
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    @DubboReference
    private SubmissionService submissionService;

    @PostMapping
    public ResponseEntity<Submission> submit(@RequestBody SubmissionCommand cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "admin", "super_admin");
        Submission submission = submissionService.submit(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(submission);
    }

    @PostMapping("/{id}/grade")
    public ResponseEntity<Submission> grade(@PathVariable Long id,
                                            @RequestBody GradeSubmissionCommand cmd,
                                            HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        // If submissionId not set on body, default to path id
        GradeSubmissionCommand effective = cmd;
        if (cmd.submissionId() == null) {
            effective = new GradeSubmissionCommand(
                    id, cmd.score(), cmd.feedback(), cmd.graderId(), cmd.idempotencyKey());
        }
        return ResponseEntity.ok(submissionService.grade(effective));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Submission> findById(@PathVariable Long id) {
        Submission submission = submissionService.findById(id);
        if (submission == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(submission);
    }

    @GetMapping
    public ResponseEntity<List<Submission>> findByAssignmentId(
            @RequestParam("assignmentId") Long assignmentId) {
        return ResponseEntity.ok(submissionService.findByAssignmentId(assignmentId));
    }
}
