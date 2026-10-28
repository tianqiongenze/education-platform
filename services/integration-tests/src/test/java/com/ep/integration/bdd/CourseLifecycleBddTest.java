package com.ep.integration.bdd;

import com.ep.integration.support.BusinessFakes.FakeCourseService;
import com.ep.integration.support.IntegrationDtos.Course;
import com.ep.integration.support.IntegrationDtos.CourseStatus;
import com.ep.integration.support.IntegrationDtos.CreateCourseCmd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Feature-driven test for {@code course-lifecycle.feature}. Each step lambda
 * calls the existing in-JVM {@link FakeCourseService} — no new behaviour is
 * invented, the Gherkin wording mirrors the fake's real API (create → DRAFT,
 * publishCourse → PUBLISHED, price carried on the create command).
 */
@Tag("integration")
class CourseLifecycleBddTest extends BddScenarioTest {

    private final FakeCourseService courseService = new FakeCourseService();
    /** State handed between steps of the currently executing scenario. */
    private Course course;

    @Override
    protected Map<String, StepRegistry.Step> defineSteps() {
        Map<String, StepRegistry.Step> steps = new LinkedHashMap<>();
        steps.put("教师 \"t1\" 已登录系统", () -> {
            // FakeCourseService keys courses by teacherId (Long); "t1" maps to teacherId 1L.
        });
        steps.put("教师创建课程 \"Java进阶\" 定价 99.0", () -> course = courseService.createCourse(
                new CreateCourseCmd("Java进阶", "BDD feature course", 1L, 4, 1, new BigDecimal("99.0"))));
        steps.put("教师发布该课程", () -> courseService.publishCourse(course.getCourseId()));
        steps.put("课程状态为 PUBLISHED", () -> {
            Course current = courseService.getCourseById(course.getCourseId());
            assertNotNull(current, "课程必须已创建且可查询");
            assertEquals(CourseStatus.PUBLISHED.getCode(), current.getStatus(),
                    "发布后课程状态必须为 PUBLISHED");
        });
        return steps;
    }

    @Test
    @DisplayName("Gherkin feature: 课程生命周期 — 教师发布课程供学生报名")
    void course_lifecycle_feature() throws Exception {
        runFeature("/features/course-lifecycle.feature");
    }
}
