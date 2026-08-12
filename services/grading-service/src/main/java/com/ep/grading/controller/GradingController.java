package com.ep.grading.controller;

import com.ep.web.common.RoleChecker;
import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.entity.GradeStage;
import com.ep.grading.grading.service.GradingService;
import jakarta.validation.Valid;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@RequestMapping("/api/v1/grading")
@Validated
public class GradingController {

    @DubboReference
    private GradingService gradingService;

    @PostMapping("/start")
    public ResponseEntity<GradePipeline> start(@Valid @RequestBody StartGradingCmd cmd,
                                               HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        GradePipeline pipeline = gradingService.startGrading(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(pipeline);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GradePipeline> findById(@PathVariable Long id) {
        GradePipeline pipeline = gradingService.findById(id);
        if (pipeline == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(pipeline);
    }

    @GetMapping("/{id}/stages")
    public ResponseEntity<List<GradeStage>> getStages(@PathVariable Long id) {
        return ResponseEntity.ok(gradingService.getStages(id));
    }

    @GetMapping
    public ResponseEntity<GradePipeline> findBySubmissionId(
            @RequestParam("submissionId") Long submissionId) {
        GradePipeline pipeline = gradingService.findBySubmissionId(submissionId);
        if (pipeline == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(pipeline);
    }
}
