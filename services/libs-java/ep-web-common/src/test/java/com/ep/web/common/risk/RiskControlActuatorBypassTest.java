package com.ep.web.common.risk;

import com.ep.web.common.RiskControlFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-241 P0-0: actuator endpoints bypass the risk-control filter entirely.
 *
 * <p>Root cause fixed here: under the 50-VU real-cluster load test every
 * {@code /actuator/health} request paid ~4 Redis commands in the sliding
 * window counter on a shared Lettuce connection — the queue stalled the
 * liveness probe past its budget and kubelet SIGTERM-ed the pod (observed as
 * exitCode 143 at 10-15 VU). Probes are not an abuse surface; counting them
 * is pure self-harm.</p>
 */
class RiskControlActuatorBypassTest {

    private RiskControlFilter newFilter(RiskControlFilter.WindowCounter counter) {
        return new RiskControlFilter(counter, 5, 200, 60, 0,
                Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneId.of("UTC")));
    }

    @Test
    @DisplayName("/actuator/health passes through WITHOUT touching the counter")
    void actuatorBypassesCounter() throws Exception {
        RiskControlFilter.WindowCounter counter = mock(RiskControlFilter.WindowCounter.class);
        RiskControlFilter filter = newFilter(counter);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        org.junit.jupiter.api.Assertions.assertEquals(200, resp.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(chain.getRequest()); // chain reached
        verify(counter, never()).increment(anyString(), eq(60));
    }

    @Test
    @DisplayName("/actuator/health/liveness and /actuator/prometheus also bypass")
    void allActuatorPathsBypass() throws Exception {
        RiskControlFilter.WindowCounter counter = mock(RiskControlFilter.WindowCounter.class);
        RiskControlFilter filter = newFilter(counter);

        for (String path : new String[]{"/actuator/health/liveness", "/actuator/prometheus"}) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("GET", path),
                    new MockHttpServletResponse(), chain);
            org.junit.jupiter.api.Assertions.assertNotNull(chain.getRequest());
        }
        verify(counter, never()).increment(anyString(), eq(60));
    }

    @Test
    @DisplayName("business paths still count (bypass is actuator-only)")
    void businessPathsStillCount() throws Exception {
        RiskControlFilter.WindowCounter counter = mock(RiskControlFilter.WindowCounter.class);
        when(counter.increment(anyString(), eq(60))).thenReturn(1L);
        RiskControlFilter filter = newFilter(counter);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/courses"),
                new MockHttpServletResponse(), chain);

        verify(counter).increment(anyString(), eq(60));
    }
}
