package com.ep.order.repository;

import com.ep.order.model.OrderModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link OrderModel}.
 * <p>
 * Query method names follow the Spring Data derivation convention so no
 * {@code @Query} is needed for the lookup-by-idempotency-key /
 * lookup-by-user-and-course / paginated-user-orders paths.
 */
@Repository
public interface OrderRepository extends JpaRepository<OrderModel, Long> {

    Optional<OrderModel> findByIdempotencyKey(String idempotencyKey);

    Optional<OrderModel> findByUserIdAndCourseId(Long userId, Long courseId);

    Page<OrderModel> findByUserIdOrderByOrderIdAsc(Long userId, Pageable pageable);
}
