package com.ep.payment.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * D-240/B: JPA repository for one-time payment tokens.
 *
 * <p>{@link #consume} is the atomic single-use guard: an UPDATE ... WHERE
 * status='ISSUED' returns 1 row for the winner and 0 for everyone else, so
 * replaying the same token twice can never double-spend regardless of the
 * application-layer check order.
 */
public interface PaymentTokenRepository extends JpaRepository<PaymentTokenEntity, String> {

    List<PaymentTokenEntity> findByAuthorizationId(String authorizationId);

    /**
     * Atomic single-use consume. Must run inside a transaction. Returns the
     * number of rows transitioned ISSUED → CONSUMED (1 = this caller won).
     */
    @Modifying
    @Query("UPDATE PaymentTokenEntity t SET t.status = 'CONSUMED', t.paymentId = :paymentId "
            + "WHERE t.tokenId = :tokenId AND t.status = 'ISSUED'")
    int consume(@Param("tokenId") String tokenId, @Param("paymentId") String paymentId);
}
