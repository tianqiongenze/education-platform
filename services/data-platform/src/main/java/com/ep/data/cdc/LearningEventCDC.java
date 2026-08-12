package com.ep.data.cdc;

import java.io.Serializable;
import java.util.Objects;

/**
 * Learning-event row image for the {@code learning_event_group} CDC stream.
 *
 * <p>Event types: 1=play, 2=complete, 3=submit, 4=quiz.
 */
public class LearningEventCDC implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Event type codes. */
    public static final int TYPE_PLAY = 1;
    public static final int TYPE_COMPLETE = 2;
    public static final int TYPE_SUBMIT = 3;
    public static final int TYPE_QUIZ = 4;

    private Long eventId;
    private Long userId;
    private Long courseId;
    private Long lessonId;
    private Integer eventType;
    private Integer durationSec;
    private Long ts;

    public LearningEventCDC() {
    }

    public LearningEventCDC(Long eventId, Long userId, Long courseId, Long lessonId,
                            Integer eventType, Integer durationSec, Long ts) {
        this.eventId = eventId;
        this.userId = userId;
        this.courseId = courseId;
        this.lessonId = lessonId;
        this.eventType = eventType;
        this.durationSec = durationSec;
        this.ts = ts;
    }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }

    public Integer getEventType() { return eventType; }
    public void setEventType(Integer eventType) { this.eventType = eventType; }

    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }

    public Long getTs() { return ts; }
    public void setTs(Long ts) { this.ts = ts; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LearningEventCDC that)) return false;
        return Objects.equals(eventId, that.eventId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(courseId, that.courseId)
                && Objects.equals(lessonId, that.lessonId)
                && Objects.equals(eventType, that.eventType)
                && Objects.equals(durationSec, that.durationSec)
                && Objects.equals(ts, that.ts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, userId, courseId, lessonId, eventType, durationSec, ts);
    }

    @Override
    public String toString() {
        return "LearningEventCDC{eventId=" + eventId + ", userId=" + userId + ", courseId=" + courseId
                + ", lessonId=" + lessonId + ", eventType=" + eventType
                + ", durationSec=" + durationSec + ", ts=" + ts + '}';
    }
}
