package com.atlasna.catalog.order.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    Optional<Order> findByOrderNumber(String orderNumber);
    /** Scoped to the owner: someone else's order is "not found" (404), never 403. */
    Optional<Order> findByOrderNumberAndUserId(String orderNumber, Long userId);
    Optional<Order> findByOrderNumberAndAssignedDriverId(String orderNumber, Long driverId);
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    List<Order> findByAssignedDriverIdAndStatusInOrderBySlotDateAscSlotStartAsc(Long driverId, List<OrderStatus> statuses);

    /** A driver's orders around a day (the end-of-day summary looks at what happened that day). */
    List<Order> findByAssignedDriverIdAndSlotDateBetweenOrderBySlotDateAscSlotStartAscIdAsc(Long driverId, java.time.LocalDate from,
                                                                                          java.time.LocalDate to);
    boolean existsByOrderNumber(String orderNumber);

    /** The order behind a gateway checkout id (webhooks name the checkout, not the order). */
    @Query("SELECT o FROM CustomerOrder o WHERE o.payment.provider = :provider AND o.payment.providerRef = :ref")
    Optional<Order> findByPaymentProviderRef(String provider, String ref);

    /** Failed cash-on-delivery attempts for a phone number since a moment (repeat-failure flag, rule 17). */
    @Query(value = """
            SELECT count(*) FROM order_status_history h JOIN orders o ON o.id = h.order_id
            WHERE o.phone = :phone AND h.to_status = 'DELIVERY_FAILED' AND h.at >= :since""", nativeQuery = true)
    long countFailedDeliveries(String phone, Instant since);
}
