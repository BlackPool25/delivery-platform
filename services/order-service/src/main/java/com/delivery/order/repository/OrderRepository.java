package com.delivery.order.repository;

import com.delivery.order.entity.Order;
import com.delivery.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Order> findByRestaurantIdAndStatus(UUID restaurantId, OrderStatus status);

    List<Order> findByCourierId(UUID courierId);

    Optional<Order> findByIdempotencyKey(UUID idempotencyKey);

    @Modifying
    @Query(value = """
        UPDATE orders
        SET courier_id = :courierId,
            status = 'DISPATCHED',
            version = version + 1,
            updated_at = NOW()
        WHERE id = :orderId
          AND courier_id IS NULL
          AND dispatch_lease_expires_at > NOW()
        """, nativeQuery = true)
    int claimDispatchLease(@Param("orderId") UUID orderId, @Param("courierId") UUID courierId);

    @Query(value = """
        SELECT o.* FROM orders o
        WHERE o.courier_id IS NULL
          AND o.dispatch_lease_expires_at > NOW()
        ORDER BY o.created_at ASC
        """, nativeQuery = true)
    List<Order> findPendingDispatchOrders();

    @Query(value = """
        SELECT o.* FROM orders o
        WHERE o.status = :status
          AND o.created_at < :cutoff
        """, nativeQuery = true)
    List<Order> findStaleOrdersByStatus(@Param("status") String status, @Param("cutoff") Instant cutoff);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE orders SET created_at = :createdAt WHERE id = :orderId", nativeQuery = true)
    int updateCreatedAt(@Param("orderId") UUID orderId, @Param("createdAt") Instant createdAt);
}
