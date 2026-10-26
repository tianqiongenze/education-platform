package com.ep.order.pricing;

import java.math.BigDecimal;

import com.ep.business.course.Course;
import com.ep.business.course.CourseService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * D-218: Production {@link CoursePricingPort} backed by the core-business
 * Dubbo RPC ({@code com.ep.business.course.CourseService}).
 *
 * <p>Fail-closed semantics: any lookup failure, missing course, or unset /
 * non-positive price results in an {@link IllegalStateException} so the order
 * is rejected rather than created with an unverified amount.
 *
 * <p>{@code check=false} keeps the service bootable when core-business is not
 * yet registered (dev/CI); the fail-closed contract still blocks order
 * creation until the pricing provider is reachable — which is the intended
 * security posture (orders cannot be created with unverified amounts).
 */
@Component
public class DubboCoursePricingPort implements CoursePricingPort {

    private static final Logger log = LoggerFactory.getLogger(DubboCoursePricingPort.class);

    @DubboReference(check = false, timeout = 10000, url = "${dubbo.ref.course.url:}")
    private CourseService courseService;

    @Override
    public BigDecimal authoritativePrice(Long courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("D-218: courseId is required for COURSE orders");
        }
        if (courseService == null) {
            throw new IllegalStateException(
                    "D-218 fail-closed: pricing provider unavailable (courseService reference not ready)");
        }
        Course course;
        try {
            course = courseService.getCourseById(courseId);
        } catch (RuntimeException e) {
            log.warn("D-218 fail-closed: pricing lookup failed for courseId={}: {}", courseId, e.getMessage());
            throw new IllegalStateException(
                    "D-218 fail-closed: course pricing lookup failed for courseId=" + courseId, e);
        }
        BigDecimal price = extractPrice(course);
        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException(
                    "D-218 fail-closed: no authoritative price for courseId=" + courseId);
        }
        return price;
    }

    /**
     * D-232: defensive extraction. When the provider round-trips the course
     * through the JSON cache (ep-cache), Dubbo deserializes it as a
     * LinkedHashMap instead of {@code Course} — accept both shapes.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal extractPrice(Object course) {
        if (course instanceof com.ep.business.course.Course c) {
            return c.getPrice();
        }
        if (course instanceof java.util.Map<?, ?> m) {
            Object v = m.get("price");
            if (v instanceof java.math.BigDecimal bd) {
                return bd;
            }
            if (v instanceof Number n) {
                return new java.math.BigDecimal(n.toString());
            }
            if (v != null) {
                return new java.math.BigDecimal(v.toString());
            }
        }
        return null;
    }
}
