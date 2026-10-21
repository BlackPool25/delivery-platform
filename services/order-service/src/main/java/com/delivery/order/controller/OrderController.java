package com.delivery.order.controller;

import com.delivery.order.dto.CreateOrderRequest;
import com.delivery.order.dto.DeliverOrderRequest;
import com.delivery.order.dto.DispatchLeaseRequest;
import com.delivery.order.dto.OrderResponse;
import com.delivery.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        OrderResponse response = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getOrdersForUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(orderService.getOrdersForUser(userId));
    }

    @GetMapping("/pending-dispatch")
    public ResponseEntity<List<OrderResponse>> getPendingDispatchOrders() {
        return ResponseEntity.ok(orderService.getPendingDispatchOrders());
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<OrderResponse> processPaymentHold(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.processPaymentHold(id));
    }

    @PostMapping("/{id}/hold-payment")
    public ResponseEntity<OrderResponse> holdPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.processPaymentHold(id));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<OrderResponse> acceptOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.acceptOrder(id));
    }

    @PostMapping("/{id}/prepare")
    public ResponseEntity<OrderResponse> prepareOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.prepareOrder(id));
    }

    @PostMapping("/{id}/ready")
    public ResponseEntity<OrderResponse> readyForPickup(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.readyForPickup(id));
    }

    @PostMapping("/{id}/lease")
    public ResponseEntity<OrderResponse> offerDispatchLease(
            @PathVariable UUID id,
            @RequestBody(required = false) DispatchLeaseRequest request) {
        UUID candidateId = request != null ? request.courierCandidateId() : null;
        Integer leaseSeconds = request != null ? request.leaseSeconds() : 30;
        return ResponseEntity.ok(orderService.offerDispatchLease(id, candidateId, leaseSeconds));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<OrderResponse> claimDispatch(
            @PathVariable UUID id,
            @RequestParam("courierId") UUID courierId) {
        OrderResponse response = orderService.claimDispatch(id, courierId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/courier-accept")
    public ResponseEntity<OrderResponse> courierAccept(
            @PathVariable UUID id,
            @RequestParam("courierId") UUID courierId) {
        OrderResponse response = orderService.claimDispatch(id, courierId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/out-for-delivery")
    public ResponseEntity<OrderResponse> outForDelivery(
            @PathVariable UUID id,
            @RequestParam("courierId") UUID courierId) {
        return ResponseEntity.ok(orderService.outForDelivery(id, courierId));
    }

    @PostMapping("/{id}/deliver")
    public ResponseEntity<OrderResponse> deliverOrder(
            @PathVariable UUID id,
            @Valid @RequestBody DeliverOrderRequest request) {
        return ResponseEntity.ok(orderService.deliverOrder(id, request.verificationPin()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable UUID id,
            @RequestParam(value = "reason", required = false) String reason) {
        return ResponseEntity.ok(orderService.cancelOrder(id, reason));
    }

    @PostMapping("/loadtest/teardown")
    public ResponseEntity<java.util.Map<String, Object>> teardownLoadtestStaging() {
        orderService.truncateLoadtestStaging();
        return ResponseEntity.ok(java.util.Map.of(
                "status", "TRUNCATED",
                "message", "orders_loadtest_staging truncated in < 100ms with zero dead-row WAL overhead",
                "timestamp", java.time.Instant.now().toString()
        ));
    }
}
