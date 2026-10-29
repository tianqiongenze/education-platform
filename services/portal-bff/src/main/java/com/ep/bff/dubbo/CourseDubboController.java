package com.ep.bff.dubbo;

import com.ep.business.course.Course;
import com.ep.business.course.CourseService;
import com.ep.business.course.CreateCourseCmd;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BFF 聚合控制器 — 课程域.
 * D-032: @DubboReference 调用 core-business CourseService, 列表/搜索/发布/归档走远程.
 * 查询型调用使用较长 timeout 以容忍分页扫描.
 *
 * D-070: Trace correlation — before each @DubboReference invocation, the MDC traceId
 * (populated by com.ep.bff.config.TraceIdFilter) must be propagated to the Dubbo RPC
 * context via com.ep.bff.config.TracePropagationUtil.propagateToRpc(), so downstream
 * core-business services can pick up the same traceId from RpcContext and bind it into their MDC.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseDubboController {

    @DubboReference(check = false, timeout = 10000, url = "${dubbo.ref.course.url:}")
    private CourseService courseService;

    // D-242 (P1-1): the Dubbo path bypassed the core HTTP RoleChecker gates —
    // any logged-in user (role=user) could create/publish/archive courses.
    // Gate at the BFF HTTP entry with the same role contract as core.
    @PostMapping
    public ResponseEntity<Course> create(@RequestBody CreateCourseCmd cmd,
                                         jakarta.servlet.http.HttpServletRequest request) {
        com.ep.web.common.RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        return ResponseEntity.ok(courseService.createCourse(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Course> getById(@PathVariable Long id) {
        Course course = courseService.getCourseById(id);
        return course == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(course);
    }

    @GetMapping
    public ResponseEntity<List<Course>> list(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(courseService.listCourses(page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Course>> search(@RequestParam String keyword) {
        return ResponseEntity.ok(courseService.searchCourses(keyword));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable Long id,
                                        jakarta.servlet.http.HttpServletRequest request) {
        com.ep.web.common.RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        courseService.publishCourse(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable Long id,
                                        jakarta.servlet.http.HttpServletRequest request) {
        com.ep.web.common.RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        courseService.archiveCourse(id);
        return ResponseEntity.noContent().build();
    }
}
