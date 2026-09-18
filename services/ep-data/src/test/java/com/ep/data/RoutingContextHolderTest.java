package com.ep.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoutingContextHolderTest {

    @AfterEach
    void cleanUp() {
        // Defensive: no leaked overrides between tests.
        RoutingContextHolder.Scope s = RoutingContextHolder.forceWrite();
        s.close();
    }

    @Test
    @DisplayName("default: no override — routing follows the transaction flag")
    void noOverrideByDefault() {
        assertNull(RoutingContextHolder.currentOverride());
    }

    @Test
    @DisplayName("forceWrite sets and clears the override")
    void forceWriteRoundTrip() {
        try (RoutingContextHolder.Scope s = RoutingContextHolder.forceWrite()) {
            assertEquals(RoutingContextHolder.Route.WRITE, RoutingContextHolder.currentOverride());
        }
        assertNull(RoutingContextHolder.currentOverride());
    }

    @Test
    @DisplayName("forceRead sets and clears the override")
    void forceReadRoundTrip() {
        try (RoutingContextHolder.Scope s = RoutingContextHolder.forceRead()) {
            assertEquals(RoutingContextHolder.Route.READ, RoutingContextHolder.currentOverride());
        }
        assertNull(RoutingContextHolder.currentOverride());
    }

    @Test
    @DisplayName("nested scopes restore the outer override (not blindly clear)")
    void nestedScopesRestoreOuter() {
        try (RoutingContextHolder.Scope outer = RoutingContextHolder.forceWrite()) {
            try (RoutingContextHolder.Scope inner = RoutingContextHolder.forceRead()) {
                assertEquals(RoutingContextHolder.Route.READ, RoutingContextHolder.currentOverride());
            }
            assertEquals(RoutingContextHolder.Route.WRITE, RoutingContextHolder.currentOverride());
        }
    }

    @Test
    @DisplayName("override survives exceptions — close still restores")
    void overrideRestoredOnException() {
        try (RoutingContextHolder.Scope s = RoutingContextHolder.forceRead()) {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException ignored) {
            // expected
        }
        assertNull(RoutingContextHolder.currentOverride());
    }
}
