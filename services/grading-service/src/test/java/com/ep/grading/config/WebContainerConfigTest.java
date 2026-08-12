package com.ep.grading.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-186: WebContainerConfig tests — verifies defaults and setters.
 */
class WebContainerConfigTest {

    @Test
    void defaults_match_spec() {
        WebContainerConfig config = new WebContainerConfig();
        assertFalse(config.isEnabled(), "webcontainer should be disabled by default (EP_DEV_ONLY guard)");
        assertEquals(256, config.getMaxMemoryMB());
        assertEquals(30, config.getTimeoutSec());
        assertEquals(16, config.getMaxCollaborators());
    }

    @Test
    void setters_round_trip() {
        WebContainerConfig config = new WebContainerConfig();
        config.setEnabled(true);
        config.setMaxMemoryMB(512);
        config.setTimeoutSec(60);
        config.setMaxCollaborators(32);
        assertTrue(config.isEnabled());
        assertEquals(512, config.getMaxMemoryMB());
        assertEquals(60, config.getTimeoutSec());
        assertEquals(32, config.getMaxCollaborators());
    }
}
