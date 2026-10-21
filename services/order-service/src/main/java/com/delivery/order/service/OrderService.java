package com.delivery.order.service;

import com.delivery.order.client.PaymentServiceClient;
import com.delivery.order.dto.CreateOrderRequest;
import com.delivery.order.dto.OrderItemRequest;
import com.delivery.order.dto.OrderItemResponse;
import com.delivery.order.dto.OrderResponse;
import com.delivery.order.entity.Order;
import com.delivery.order.entity.OrderItem;
import com.delivery.order.entity.OrderStatus;
import com.delivery.order.entity.OutboxEvent;
import com.delivery.order.exception.IllegalOrderStateException;
import com.delivery.order.exception.InvalidVerificationPinException;
import com.delivery.order.exception.LeaseExpiredOrClaimedException;
import com.delivery.order.exception.OrderNotFoundException;
import com.delivery.order.repository.OrderRepository;
import com.delivery.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int SRID_WGS84 = 4326;
    private static final BigDecimal DEFAULT_DELIVERY_FEE = new BigDecimal("3.99");
    private static final BigDecimal TAX_RATE = new BigDecimal("0.085"); // 8.5%

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;

    public OrderService(OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        PaymentServiceClient paymentServiceClient,
                        ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paymentServiceClient = paymentServiceClient;
        this.objectMapper = objectMapper;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), SRID_WGS84);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        return createOrder(request, null, false);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, UUID idempotencyKeyHeader) {
        return createOrder(request, idempotencyKeyHeader, false);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, UUID idempotencyKeyHeader, boolean autoHold) {
        UUID effectiveIdempotencyKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : request.idempotencyKey();

        if (effectiveIdempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(effectiveIdempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent request match found for key: {}", effectiveIdempotencyKey);
                return mapToResponse(existing.get());
            }
        }

        Point dropoffLocation = geometryFactory.createPoint(
                new Coordinate(request.dropoffLongitude(), request.dropoffLatitude())
        );
        dropoffLocation.setSRID(SRID_WGS84);

        Order order = new Order(
                request.userId(),
                request.restaurantId(),
                dropoffLocation,
                request.dropoffBuilding(),
                request.dropoffFloor(),
                request.dropoffRoom(),
                request.dropoffGateCode(),
                request.dropoffNotes(),
                request.verificationPin()
        );

        if (effectiveIdempotencyKey != null) {
            order.setIdempotencyKey(effectiveIdempotencyKey);
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderItemRequest itemReq : request.items()) {
            BigDecimal lineTotal = itemReq.unitPrice().multiply(BigDecimal.valueOf(itemReq.quantity()));
            subtotal = subtotal.add(lineTotal);

            OrderItem item = new OrderItem(
                    itemReq.dishId(),
                    itemReq.dishName(),
                    itemReq.unitPrice(),
                    itemReq.quantity(),
                    itemReq.customizationNotes()
            );
            order.addItem(item);
        }

        BigDecimal tax = subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(DEFAULT_DELIVERY_FEE).add(tax).setScale(2, RoundingMode.HALF_UP);

        order.setSubtotal(subtotal);
        order.setDeliveryFee(DEFAULT_DELIVERY_FEE);
        order.setTax(tax);
        order.setTotalAmount(total);
        order.setStatus(OrderStatus.PLACED);

        Order savedOrder = orderRepository.save(order);
        emitOutboxEvent(savedOrder, "OrderPlaced");

        log.info("Order created: {} (status: {}, total: {}, items: {})",
                savedOrder.getId(), savedOrder.getStatus(), savedOrder.getTotalAmount(), savedOrder.getItems().size());

        if (autoHold) {
            return processPaymentHold(savedOrder.getId());
        }

        return mapToResponse(savedOrder);
    }

    @Transactional
    public OrderResponse processPaymentHold(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new IllegalOrderStateException("Cannot process payment hold for order in status: " + order.getStatus());
        }

        order.setStatus(OrderStatus.PAYMENT_PENDING);
        order.setUpdatedAt(Instant.now());
        Order pendingOrder = orderRepository.save(order);
        emitOutboxEvent(pendingOrder, "OrderPaymentPending");

        log.info("Order {} transitioned to PAYMENT_PENDING. Initiating ledger HOLD...", orderId);

        PaymentServiceClient.PaymentResult paymentResult = paymentServiceClient.holdPayment(
                pendingOrder.getId(),
                pendingOrder.getUserId(),
                pendingOrder.getTotalAmount(),
                pendingOrder.getIdempotencyKey()
        );

        if (paymentResult.success()) {
            pendingOrder.setStatus(OrderStatus.PAYMENT_CONFIRMED);
            pendingOrder.setUpdatedAt(Instant.now());
            Order confirmed = orderRepository.save(pendingOrder);
            emitOutboxEvent(confirmed, "OrderPaymentConfirmed");
            log.info("Order payment confirmed: {} (total: {})", confirmed.getId(), confirmed.getTotalAmount());
            return mapToResponse(confirmed);
        } else if (paymentResult.error() != null && paymentResult.error().contains("INSUFFICIENT_FUNDS")) {
            pendingOrder.setStatus(OrderStatus.CANCELLED);
            pendingOrder.setCancellationReason("INSUFFICIENT_FUNDS");
            pendingOrder.setUpdatedAt(Instant.now());
            Order cancelled = orderRepository.save(pendingOrder);
            emitOutboxEvent(cancelled, "OrderCancelled", Map.of("cancellationReason", "INSUFFICIENT_FUNDS"));
            log.warn("Order cancelled due to insufficient funds: {}", pendingOrder.getId());
            return mapToResponse(cancelled);
        } else {
            // Simulated payment delay or temporary unreachable payment gateway:
            // Order remains in PAYMENT_PENDING. The SagaTimeoutCoordinator will abort if stale (>90s).
            log.info("Order {} held in PAYMENT_PENDING state pending asynchronous resolution", pendingOrder.getId());
            return mapToResponse(pendingOrder);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForUser(UUID userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getPendingDispatchOrders() {
        return orderRepository.findPendingDispatchOrders()
                .stream().map(this::mapToResponse).toList();
    }

    @Transactional
    public OrderResponse acceptOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PAYMENT_CONFIRMED) {
            throw new IllegalOrderStateException("Cannot accept order in status: " + order.getStatus() +
                    ". Expected PAYMENT_CONFIRMED");
        }

        order.setStatus(OrderStatus.ACCEPTED);
        order.setUpdatedAt(Instant.now());
        Order updated = orderRepository.save(order);
        emitOutboxEvent(updated, "OrderAccepted");
        log.info("Order {} accepted by merchant", orderId);
        return mapToResponse(updated);
    }

    @Transactional
    public OrderResponse prepareOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.ACCEPTED) {
            throw new IllegalOrderStateException("Cannot prepare order in status: " + order.getStatus() +
                    ". Expected ACCEPTED");
        }

        order.setStatus(OrderStatus.PREPARING);
        order.setUpdatedAt(Instant.now());
        Order updated = orderRepository.save(order);
        emitOutboxEvent(updated, "OrderPreparing");
        log.info("Order {} kitchen preparation started", orderId);
        return mapToResponse(updated);
    }

    @Transactional
    public OrderResponse readyForPickup(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PREPARING && order.getStatus() != OrderStatus.ACCEPTED) {
            throw new IllegalOrderStateException("Cannot set readyForPickup in status: " + order.getStatus());
        }

        order.setStatus(OrderStatus.READY_FOR_PICKUP);
        order.setUpdatedAt(Instant.now());
        Order updated = orderRepository.save(order);
        emitOutboxEvent(updated, "OrderReadyForPickup");
        log.info("Order {} is ready for pickup", orderId);
        return mapToResponse(updated);
    }

    @Transactional
    public OrderResponse offerDispatchLease(UUID orderId, UUID courierCandidateId, Integer leaseSeconds) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        int duration = (leaseSeconds != null && leaseSeconds > 0) ? leaseSeconds : 30;
        Instant leaseExpiresAt = Instant.now().plusSeconds(duration);

        order.setDispatchLeaseExpiresAt(leaseExpiresAt);
        order.setUpdatedAt(Instant.now());
        Order updated = orderRepository.save(order);

        emitOutboxEvent(updated, "OrderLeaseOffered", Map.of(
                "candidateCourierId", courierCandidateId != null ? courierCandidateId.toString() : "",
                "leaseExpiresAt", leaseExpiresAt.toString(),
                "durationSeconds", duration
        ));

        log.info("Order {} offered on targeted 1-to-1 lease expiring at {}", orderId, leaseExpiresAt);
        return mapToResponse(updated);
    }

    @Transactional
    public OrderResponse claimDispatch(UUID orderId, UUID courierId) {
        int updatedRows = orderRepository.claimDispatchLease(orderId, courierId);
        if (updatedRows > 0) {
            Order order = orderRepository.findById(orderId).orElseThrow();
            emitOutboxEvent(order, "OrderCourierAssigned", Map.of("courierId", courierId.toString()));
            log.info("Order {} successfully claimed by courier {}", orderId, courierId);
            return mapToResponse(order);
        } else {
            log.warn("Courier {} failed to claim order {}: lease expired or already claimed", courierId, orderId);
            throw new LeaseExpiredOrClaimedException(orderId);
        }
    }

    @Transactional
    public OrderResponse outForDelivery(UUID orderId, UUID courierId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.DISPATCHED) {
            throw new IllegalOrderStateException("Cannot set out-for-delivery in status: " + order.getStatus() +
                    ". Expected DISPATCHED");
        }

        if (order.getCourierId() == null || !order.getCourierId().equals(courierId)) {
            throw new IllegalOrderStateException("Order courier does not match claiming courier");
        }

        order.setStatus(OrderStatus.OUT_FOR_DELIVERY);
        order.setUpdatedAt(Instant.now());
        Order updated = orderRepository.save(order);
        emitOutboxEvent(updated, "OrderOutForDelivery");
        log.info("Order {} is now OUT_FOR_DELIVERY with courier {}", orderId, courierId);
        return mapToResponse(updated);
    }

    @Transactional
    public OrderResponse deliverOrder(UUID orderId, String pin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.OUT_FOR_DELIVERY && order.getStatus() != OrderStatus.DISPATCHED) {
            throw new IllegalOrderStateException("Cannot deliver order in status: " + order.getStatus());
        }

        if (order.getVerificationPin() == null || !order.getVerificationPin().equals(pin)) {
            log.warn("Invalid verification PIN supplied for order {}: expected {}, received {}",
                    orderId, order.getVerificationPin(), pin);
            throw new InvalidVerificationPinException("Invalid verification handshake PIN: " + pin);
        }

        order.setStatus(OrderStatus.DELIVERED);
        order.setUpdatedAt(Instant.now());
        emitOutboxEvent(order, "OrderDelivered");

        // Capture payment funds from customer to restaurant ledger
        paymentServiceClient.capturePayment(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                null,
                UUID.randomUUID()
        );

        order.setStatus(OrderStatus.SETTLED);
        order.setUpdatedAt(Instant.now());
        Order settled = orderRepository.save(order);
        emitOutboxEvent(settled, "OrderSettled");

        log.info("Order {} successfully delivered and settled with merchant", orderId);
        return mapToResponse(settled);
    }

    @Transactional
    public OrderResponse cancelOrder(UUID orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.SETTLED) {
            throw new IllegalOrderStateException("Cannot cancel an already delivered or settled order: " + orderId);
        }

        // Refund held funds if payment was confirmed
        if (order.getStatus() == OrderStatus.PAYMENT_CONFIRMED ||
                order.getStatus() == OrderStatus.ACCEPTED ||
                order.getStatus() == OrderStatus.PREPARING ||
                order.getStatus() == OrderStatus.READY_FOR_PICKUP) {
            paymentServiceClient.refundPayment(
                    order.getId(),
                    order.getUserId(),
                    order.getTotalAmount(),
                    reason != null ? reason : "CUSTOMER_OR_MERCHANT_CANCELLATION",
                    UUID.randomUUID()
            );
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason != null ? reason : "MANUAL_CANCELLATION");
        order.setUpdatedAt(Instant.now());
        Order cancelled = orderRepository.save(order);
        emitOutboxEvent(cancelled, "OrderCancelled", Map.of("cancellationReason", cancelled.getCancellationReason()));

        log.info("Order {} cancelled: {}", orderId, cancelled.getCancellationReason());
        return mapToResponse(cancelled);
    }

    private void emitOutboxEvent(Order order, String eventType) {
        emitOutboxEvent(order, eventType, Map.of());
    }

    private void emitOutboxEvent(Order order, String eventType, Map<String, Object> extraPayload) {
        try {
            Map<String, Object> payloadMap = new HashMap<>(Map.of(
                    "orderId", order.getId().toString(),
                    "userId", order.getUserId().toString(),
                    "restaurantId", order.getRestaurantId().toString(),
                    "status", order.getStatus().name(),
                    "totalAmount", order.getTotalAmount(),
                    "verificationPin", order.getVerificationPin(),
                    "dropoffBuilding", order.getDropoffBuilding(),
                    "dropoffRoom", order.getDropoffRoom() != null ? order.getDropoffRoom() : "",
                    "createdAt", order.getCreatedAt().toString()
            ));
            if (order.getCourierId() != null) {
                payloadMap.put("courierId", order.getCourierId().toString());
            }
            if (order.getDispatchLeaseExpiresAt() != null) {
                payloadMap.put("dispatchLeaseExpiresAt", order.getDispatchLeaseExpiresAt().toString());
            }
            if (order.getCancellationReason() != null) {
                payloadMap.put("cancellationReason", order.getCancellationReason());
            }
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
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for order: {}", order.getId(), e);
            throw new RuntimeException("Outbox serialization failure", e);
        }
    }

    private OrderResponse mapToResponse(Order order) {
        Double lat = order.getDropoffLocation() != null ? order.getDropoffLocation().getY() : null;
        Double lng = order.getDropoffLocation() != null ? order.getDropoffLocation().getX() : null;

        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getDishId(),
                        item.getDishName(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        item.getCustomizationNotes(),
                        item.getCreatedAt()
                )).toList();

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getRestaurantId(),
                order.getCourierId(),
                order.getStatus(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTax(),
                order.getTotalAmount(),
                lat,
                lng,
                order.getDropoffBuilding(),
                order.getDropoffFloor(),
                order.getDropoffRoom(),
                order.getDropoffGateCode(),
                order.getDropoffNotes(),
                order.getVerificationPin(),
                order.getDispatchLeaseExpiresAt(),
                order.getCityId(),
                order.getIdempotencyKey(),
                order.getCancellationReason(),
                order.getVersion(),
                itemResponses,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
