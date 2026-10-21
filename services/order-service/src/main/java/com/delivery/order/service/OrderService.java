package com.delivery.order.service;

import com.delivery.order.dto.CreateOrderRequest;
import com.delivery.order.dto.OrderItemRequest;
import com.delivery.order.dto.OrderItemResponse;
import com.delivery.order.dto.OrderResponse;
import com.delivery.order.entity.Order;
import com.delivery.order.entity.OrderItem;
import com.delivery.order.entity.OrderStatus;
import com.delivery.order.entity.OutboxEvent;
import com.delivery.order.exception.DuplicateOrderException;
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
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;

    public OrderService(OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), SRID_WGS84);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        if (request.idempotencyKey() != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotent request match found for key: {}", request.idempotencyKey());
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

        if (request.idempotencyKey() != null) {
            order.setIdempotencyKey(request.idempotencyKey());
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

        // Transactional Outbox pattern: write outbox event atomically in same DB transaction
        emitOutboxEvent(savedOrder, "OrderPlaced");

        log.info("Order created: {} (total: {}, items: {})",
                savedOrder.getId(), savedOrder.getTotalAmount(), savedOrder.getItems().size());

        return mapToResponse(savedOrder);
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

    @Transactional
    public boolean claimDispatch(UUID orderId, UUID courierId) {
        int updated = orderRepository.claimDispatchLease(orderId, courierId);
        if (updated > 0) {
            Order order = orderRepository.findById(orderId).orElseThrow();
            emitOutboxEvent(order, "OrderCourierAssigned");
            log.info("Order {} claimed by courier {}", orderId, courierId);
            return true;
        }
        return false;
    }

    private void emitOutboxEvent(Order order, String eventType) {
        try {
            Map<String, Object> payloadMap = Map.of(
                    "orderId", order.getId().toString(),
                    "userId", order.getUserId().toString(),
                    "restaurantId", order.getRestaurantId().toString(),
                    "status", order.getStatus().name(),
                    "totalAmount", order.getTotalAmount(),
                    "verificationPin", order.getVerificationPin(),
                    "dropoffBuilding", order.getDropoffBuilding(),
                    "dropoffRoom", order.getDropoffRoom() != null ? order.getDropoffRoom() : "",
                    "createdAt", order.getCreatedAt().toString()
            );
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
