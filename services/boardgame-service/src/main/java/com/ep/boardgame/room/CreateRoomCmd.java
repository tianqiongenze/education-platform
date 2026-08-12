package com.ep.boardgame.room;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/**
 * DTO for creating a game room.
 */
public class CreateRoomCmd implements Serializable {

    private Long tenantId;
    private Long courseId;
    private Long lessonId;
    @NotBlank
    private String roomName;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
}
