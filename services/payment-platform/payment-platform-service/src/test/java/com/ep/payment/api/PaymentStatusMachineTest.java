package com.ep.payment.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-235/A1: the explicit payment transition table. Terminal states never exit;
 * the synchronous-channel shortcut (PENDING→SUCCESS) is legal; everything the
 * doc calls 异常处理 (FAILED) is reachable only from live states.
 */
class PaymentStatusMachineTest {

    @Test
    @DisplayName("PENDING can go to PROCESSING, SUCCESS (sync channel) or FAILED")
    void pendingExits() {
        assertTrue(PaymentStatusMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.PROCESSING));
        assertTrue(PaymentStatusMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.SUCCESS));
        assertTrue(PaymentStatusMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.FAILED));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("PROCESSING can only settle or fail")
    void processingExits() {
        assertTrue(PaymentStatusMachine.canTransition(PaymentStatus.PROCESSING, PaymentStatus.SUCCESS));
        assertTrue(PaymentStatusMachine.canTransition(PaymentStatus.PROCESSING, PaymentStatus.FAILED));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.PROCESSING, PaymentStatus.PENDING));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.PROCESSING, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("SUCCESS only exits by refund")
    void successExits() {
        assertTrue(PaymentStatusMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.FAILED));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.PROCESSING));
    }

    @Test
    @DisplayName("terminal states never exit")
    void terminalStates() {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.FAILED, to));
            assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.REFUNDED, to));
        }
    }

    @Test
    @DisplayName("null and self transitions are illegal")
    void nullAndSelf() {
        assertFalse(PaymentStatusMachine.canTransition(null, PaymentStatus.SUCCESS));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.PENDING, null));
        assertFalse(PaymentStatusMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.SUCCESS));
    }

    @Test
    @DisplayName("requireTransition throws on illegal moves")
    void requireThrows() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStatusMachine.requireTransition(PaymentStatus.FAILED, PaymentStatus.SUCCESS));
        assertThrows(IllegalStateException.class,
                () -> PaymentStatusMachine.requireTransition(PaymentStatus.PENDING, PaymentStatus.REFUNDED));
    }
}
