package com.ep.grading.ide.dto;

import java.io.Serializable;

/**
 * D-186: Command for creating an IDE session.
 *
 * @param tenantId       owning tenant (required)
 * @param userId         owner user id (required)
 * @param mode           TEACHING or JUDGE; defaults to TEACHING when null/blank
 * @param courseId       optional course context
 * @param lessonId       optional lesson context
 * @param idleTimeoutMin minutes of inactivity before the session flips to IDLE;
 *                       defaults to 30 when null or ≤ 0
 */
public record CreateIdeSessionCmd(
        Long tenantId,
        Long userId,
        String mode,
        Long courseId,
        Long lessonId,
        Integer idleTimeoutMin
) implements Serializable {
}
