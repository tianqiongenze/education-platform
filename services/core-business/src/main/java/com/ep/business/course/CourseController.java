package com.ep.business.course;

import com.ep.web.common.RoleChecker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/courses")
@Validated
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping
    public ResponseEntity<Course> create(@Valid @RequestBody CreateCourseCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        return ResponseEntity.ok(courseService.createCourse(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Course> getById(@PathVariable Long id) {
        Course course = courseService.getCourseById(id);
        return course == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(course);
    }

    @GetMapping
    public ResponseEntity<List<Course>> list(@RequestParam(defaultValue = "1") Integer page,
                                             @RequestParam(defaultValue = "10") Integer size) {
        return ResponseEntity.ok(courseService.listCourses(page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Course>> search(@RequestParam String keyword) {
        return ResponseEntity.ok(courseService.searchCourses(keyword));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        courseService.publishCourse(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable Long id, HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin", "teacher");
        courseService.archiveCourse(id);
        return ResponseEntity.noContent().build();
    }
}
