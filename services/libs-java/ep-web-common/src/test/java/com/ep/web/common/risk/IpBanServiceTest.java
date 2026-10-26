package com.ep.web.common.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-236 tests: automatic IP ban escalation (in-memory path — the Redis path is
 * contract-identical SET/EXISTS/DEL).
 */
class IpBanServiceTest {

    /** Mutable epoch-millis clock. */
    private static final class FakeClock extends Clock {
        final AtomicLong millis = new AtomicLong(System.currentTimeMillis());

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    @DisplayName("violations below the threshold do not ban")
    void belowThresholdNoBan() {
        IpBanService service = new IpBanService(null, 3, Duration.ofMinutes(30), new FakeClock());
        assertFalse(service.recordViolation("1.2.3.4"));
        assertFalse(service.recordViolation("1.2.3.4"));
        assertFalse(service.isBanned("1.2.3.4"));
    }

    @Test
    @DisplayName("the Nth violation bans the IP")
    void thresholdBans() {
        IpBanService service = new IpBanService(null, 3, Duration.ofMinutes(30), new FakeClock());
        service.recordViolation("1.2.3.4");
        service.recordViolation("1.2.3.4");
        assertTrue(service.recordViolation("1.2.3.4"), "3rd violation triggers the ban");
        assertTrue(service.isBanned("1.2.3.4"));
    }

    @Test
    @DisplayName("ban expires after the TTL and the violation ledger reset")
    void banExpires() {
        FakeClock clock = new FakeClock();
        IpBanService service = new IpBanService(null, 2, Duration.ofMinutes(30), clock);
        service.recordViolation("5.6.7.8");
        assertTrue(service.recordViolation("5.6.7.8"));
        assertTrue(service.isBanned("5.6.7.8"));

        clock.millis.addAndGet(Duration.ofMinutes(31).toMillis());
        assertFalse(service.isBanned("5.6.7.8"), "ban must expire after 30 minutes");
    }

    @Test
    @DisplayName("after a ban the violation ledger restarts (post-unban re-ban works)")
    void ledgerResetsAfterBan() {
        IpBanService service = new IpBanService(null, 2, Duration.ofMinutes(5), new FakeClock());
        service.recordViolation("7.7.7.7");
        assertTrue(service.recordViolation("7.7.7.7")); // banned
        service.unban("7.7.7.7");
        assertFalse(service.isBanned("7.7.7.7"));
        // fresh ledger: one more violation must NOT ban again
        assertFalse(service.recordViolation("7.7.7.7"));
    }

    @Test
    @DisplayName("blank/unknown IPs are never banned (NAT-guard for unresolved sockets)")
    void unknownIpNeverBanned() {
        IpBanService service = new IpBanService(null, 1, Duration.ofMinutes(5), new FakeClock());
        assertFalse(service.recordViolation(null));
        assertFalse(service.recordViolation(""));
        assertFalse(service.recordViolation("unknown"));
    }

    @Test
    @DisplayName("different IPs escalate independently")
    void independentIps() {
        IpBanService service = new IpBanService(null, 2, Duration.ofMinutes(5), new FakeClock());
        service.recordViolation("8.8.8.8");
        assertFalse(service.recordViolation("9.9.9.9"), "another IP starts from zero");
        assertTrue(service.recordViolation("8.8.8.8"));
    }

    @Test
    @DisplayName("invalid configuration is rejected")
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new IpBanService(null, 0, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new IpBanService(null, 3, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new IpBanService(null, 3, null));
    }
}
