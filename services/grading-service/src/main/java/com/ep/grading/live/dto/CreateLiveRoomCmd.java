package com.ep.grading.live.dto;

import java.io.Serializable;

/**
 * D-187: Command for creating a live room.
 *
 * @param tenantId    owning tenant
 * @param userId      host user id
 * @param mode        SRS (large broadcast) or SFU (interactive low-latency)
 * @param viewerLimit max concurrent viewers; defaults to 1000 when null
 */
public record CreateLiveRoomCmd(
        Long tenantId,
        Long userId,
        String mode,
        Integer viewerLimit
) implements Serializable {
    public CreateLiveRoomCmd {
        if (viewerLimit == null) {
            viewerLimit = 1000;
        }
    }
}
