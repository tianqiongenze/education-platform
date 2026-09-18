package com.ep.order.pricing;

import java.math.BigDecimal;

/**
 * D-218: Port for resolving the <b>authoritative</b> course price.
 *
 * <p>Risk-control contract: the client-supplied order amount is never
 * trusted. {@code OrderServiceImpl} resolves the price server-side through
 * this port and overrides the client value; the port is <b>fail-closed</b> —
 * implementations must throw rather than return a fallback when the
 * authoritative price cannot be determined (money safety over availability).
 */
public interface CoursePricingPort {

    /**
     * Resolves the authoritative unit price for a course.
     *
     * @param courseId course id (never null)
     * @return the authoritative price (guaranteed non-null and &gt; 0)
     * @throws IllegalStateException when the price cannot be resolved
     *                               (course missing, price unset, or lookup
     *                               failure) — fail-closed
     */
    BigDecimal authoritativePrice(Long courseId);
}
