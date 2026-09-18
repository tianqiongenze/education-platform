package com.ep.bff.event;

/**
 * Port for course-detail cache warm-up (D-event-loop: 事件驱动闭环).
 * <p>
 * Implemented by {@link HttpCourseWarmupClient}, which triggers the
 * core-business course-detail endpoint so its L1/L2 cache (ep-cache) fills
 * before the first student request arrives. Kept as an interface so the
 * {@link CoursePublishedConsumer} can be unit-tested with a Mockito mock
 * (pure JUnit + Mockito, no Spring context, no broker).
 */
public interface CourseWarmupClient {

    /**
     * Best-effort warm-up of the course-detail cache. Never throws; failures
     * are logged by the implementation.
     *
     * @param courseId published course id
     */
    void warmupCourseDetail(long courseId);
}
