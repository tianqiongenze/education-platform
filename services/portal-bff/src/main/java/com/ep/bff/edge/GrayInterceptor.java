package com.ep.bff.edge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gray/canary traffic interceptor (SDD §5.1 GrayInterceptor).
 *
 * <p>Implements gray-release traffic splitting based on user ID hash.
 * A configurable percentage of traffic is tagged as "canary" and routed
 * to the gray deployment cell.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Read {@code X-User-Id} header (set by {@code AuthFilter}).</li>
 *   <li>Hash the user ID and compute {@code hash % 100}.</li>
 *   <li>If hash &lt; {@code ep.gray.ratio}, tag as "canary".</li>
 *   <li>Set {@code X-Gray-Tag} response header: "stable" or "canary".</li>
 * </ol>
 *
 * <p>Conditionally activated when {@code ep.gray.enabled=true} (default false).
 *
 * <p>Users in the canary group are consistently routed to the canary cell
 * (sticky by user ID), ensuring a coherent user experience during gray release.
 */
public class GrayInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrayInterceptor.class);

    /** Response header name for the gray tag. */
    public static final String GRAY_TAG_HEADER = "X-Gray-Tag";

    /** Tag value for stable (non-canary) traffic. */
    public static final String TAG_STABLE = "stable";

    /** Tag value for canary (gray) traffic. */
    public static final String TAG_CANARY = "canary";

    private final double grayRatio;

    public GrayInterceptor(@Value("${ep.gray.ratio:0.0}") double grayRatio) {
        this.grayRatio = Math.max(0.0, Math.min(100.0, grayRatio));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String userId = request.getHeader("X-User-Id");
        String tag = computeTag(userId);
        response.setHeader(GRAY_TAG_HEADER, tag);

        if (TAG_CANARY.equals(tag)) {
            log.debug("[GrayInterceptor] User {} routed to canary (ratio={})",
                    userId, grayRatio);
        }

        return true; // always proceed — gray tag is advisory, not blocking
    }

    /**
     * Compute the gray tag for a user ID.
     *
     * @param userId the user ID (from X-User-Id header, may be null)
     * @return {@value #TAG_STABLE} or {@value #TAG_CANARY}
     */
    String computeTag(String userId) {
        if (grayRatio <= 0.0) {
            return TAG_STABLE;
        }
        if (userId == null || userId.isBlank()) {
            // No user ID — use request-based hashing (falls to stable by default)
            return TAG_STABLE;
        }
        // Hash user ID and compute percentage
        int hash = Math.abs(userId.hashCode());
        int percentile = hash % 100;
        return percentile < grayRatio ? TAG_CANARY : TAG_STABLE;
    }
}
