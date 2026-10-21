package com.delivery.order.saga;

import com.delivery.order.client.PaymentServiceClient;
import com.delivery.order.entity.Order;
import com.delivery.order.entity.OrderStatus;
import com.delivery.order.entity.OutboxEvent;
import com.delivery.order.repository.OrderRepository;
import com.delivery.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SagaTimeoutCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutCoordinator.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final ObjectMapper objectMapper;

    @Value("${order.saga.payment-timeout-seconds:90}")
    private long paymentTimeoutSeconds;

    public SagaTimeoutCoordinator(OrderRepository orderRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  PaymentServiceClient paymentServiceClient,
                                  ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paymentServiceClient = paymentServiceClient;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${order.saga.interval-ms:15000}")
    @Transactional
    public void abortStalePendingOrdersScheduled() {
        abortStaleOrders(Duration.ofSeconds(paymentTimeoutSeconds));
    }

    @Transactional
    public int abortStaleOrders(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        log.debug("SagaTimeoutCoordinator checking for stale PAYMENT_PENDING orders created before: {}", cutoff);

        List<Order> staleOrders = orderRepository.findStaleOrdersByStatus(
                OrderStatus.PAYMENT_PENDING.name(), cutoff
        );

        int abortedCount = 0;
        for (Order order : staleOrders) {
            log.warn("SagaTimeoutCoordinator aborting stale order {} stuck in PAYMENT_PENDING for > {} seconds",
                    order.getId(), timeout.toSeconds());

            // 1. Transition status to CANCELLED with reason PAYMENT_TIMEOUT
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationReason("PAYMENT_TIMEOUT");
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);

            // 2. Compensating Saga action: attempt refund/release with payment ledger if held
            try {
                paymentServiceClient.refundPayment(
                        order.getId(),
                        order.getUserId(),
                        order.getTotalAmount(),
                        "SAGA_PAYMENT_TIMEOUT",
                        UUID.randomUUID()
                );
            } catch (Exception e) {
                log.warn("Saga compensation refund call failed (or was not held) for order {}: {}",
                        order.getId(), e.getMessage());
            }

            // 3. Emit Transactional Outbox Event for Debezium CDC pipeline
            emitOutboxEvent(order, "OrderCancelled", Map.of(
                    "reason", "PAYMENT_TIMEOUT",
                    "abortedBy", "SagaTimeoutCoordinator",
                    "timeoutSeconds", timeout.toSeconds()
            ));

            abortedCount++;
        }

        if (abortedCount > 0) {
            log.info("SagaTimeoutCoordinator aborted {} stale orders", abortedCount);
        }
        return abortedCount;
    }

    private void emitOutboxEvent(Order order, String eventType, Map<String, Object> extraPayload) {
        try {
            Map<String, Object> payloadMap = new java.util.HashMap<>(Map.of(
                    "orderId", order.getId().toString(),
                    "userId", order.getUserId().toString(),
                    "restaurantId", order.getRestaurantId().toString(),
                    "status", order.getStatus().name(),
                    "totalAmount", order.getTotalAmount(),
                    "cancellationReason", order.getCancellationReason() != null ? order.getCancellationReason() : "",
                    "createdAt", order.getCreatedAt().toString()
            ));
            payloadMap.putAll(extraPayload);

            String payloadJson = objectMapper.writeValueAsString(payloadMap);
            String tracingJson = objectMapper.writeValueAsString(Map.of("traceparent", UUID.randomUUID().toString()));

            OutboxEvent event = new OutboxEvent(
                    UUID.randomUUID(),
                    order.getCreatedAt(),
                    "ORDER",
                    order.getId(),
                    eventType,
                    payloadJson
            );
            event.setTracingHeaders(tracingJson);
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event payload for order: {}", order.getId(), e);
            throw new RuntimeException("Outbox serialization failure", e);
        }
    }
}
