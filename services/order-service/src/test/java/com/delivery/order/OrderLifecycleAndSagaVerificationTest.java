package com.delivery.order;

import com.delivery.order.client.PaymentServiceClient;
import com.delivery.order.dto.CreateOrderRequest;
import com.delivery.order.dto.OrderItemRequest;
import com.delivery.order.dto.OrderResponse;
import com.delivery.order.entity.Order;
import com.delivery.order.entity.OrderStatus;
import com.delivery.order.entity.OutboxEvent;
import com.delivery.order.exception.InvalidVerificationPinException;
import com.delivery.order.exception.LeaseExpiredOrClaimedException;
import com.delivery.order.repository.OrderRepository;
import com.delivery.order.repository.OutboxEventRepository;
import com.delivery.order.saga.SagaTimeoutCoordinator;
import com.delivery.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderLifecycleAndSagaVerificationTest {

    @Container
    static PostgreSQLContainer<?> postgis = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SagaTimeoutCoordinator sagaCoordinator;

    @MockitoBean
    private PaymentServiceClient paymentServiceClient;

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Verification Gate: Automated Order Lifecycle (Checkout -> Ledger Hold -> Kitchen Accept -> Driver Assignment -> PIN Handshake)")
    void testCompleteOrderLifecycleWithOutboxCdcEvents() {
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        String pin = "482910";

        // Mock payment service success for hold and capture
        when(paymentServiceClient.holdPayment(any(), eq(userId), any(), any()))
                .thenReturn(new PaymentServiceClient.PaymentResult(true, "HOLD_SUCCESSFUL", null));
        when(paymentServiceClient.capturePayment(any(), eq(userId), any(), any(), any()))
                .thenReturn(new PaymentServiceClient.PaymentResult(true, "CAPTURE_SUCCESSFUL", null));

        // 1. Checkout (Idempotent) -> PLACED (version: 0)
        CreateOrderRequest request = new CreateOrderRequest(
                userId,
                restaurantId,
                -122.4194, // dropoff longitude
                37.7749,   // dropoff latitude
                "Apex Tower 4",
                "Floor 12",
                "Suite 1204",
                "GATE#9871",
                "Leave at doorstep",
                pin,
                idempotencyKey,
                List.of(
                        new OrderItemRequest(UUID.randomUUID(), "Truffle Burger", new BigDecimal("18.50"), 2, "Medium rare"),
                        new OrderItemRequest(UUID.randomUUID(), "Garlic Fries", new BigDecimal("6.00"), 1, "Extra crispy")
                )
        );

        OrderResponse orderRes = orderService.createOrder(request, idempotencyKey);
        assertThat(orderRes).isNotNull();
        assertThat(orderRes.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(orderRes.version()).isEqualTo(0L);
        assertThat(orderRes.totalAmount()).isGreaterThan(new BigDecimal("43.00")); // items + fee + tax
        UUID orderId = orderRes.id();

        // Verify Idempotency: calling checkout with same key returns identical order without duplication
        OrderResponse duplicateCheck = orderService.createOrder(request, idempotencyKey);
        assertThat(duplicateCheck.id()).isEqualTo(orderId);
        assertThat(duplicateCheck.version()).isEqualTo(orderRes.version());

        // 2. Ledger Hold -> PAYMENT_PENDING -> PAYMENT_CONFIRMED
        OrderResponse holdRes = orderService.processPaymentHold(orderId);
        assertThat(holdRes.status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMED);

        // 3. Kitchen / Merchant Accept
        OrderResponse acceptedRes = orderService.acceptOrder(orderId);
        assertThat(acceptedRes.status()).isEqualTo(OrderStatus.ACCEPTED);

        // 4. Kitchen Prepare
        OrderResponse preparingRes = orderService.prepareOrder(orderId);
        assertThat(preparingRes.status()).isEqualTo(OrderStatus.PREPARING);

        // 5. Kitchen Ready for Pickup
        OrderResponse readyRes = orderService.readyForPickup(orderId);
        assertThat(readyRes.status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);

        // 6. Targeted 1-to-1 Dispatch Lease Offer (30 seconds exclusive lease)
        OrderResponse leasedRes = orderService.offerDispatchLease(orderId, courierId, 30);
        assertThat(leasedRes.dispatchLeaseExpiresAt()).isAfter(Instant.now());

        // 7. Courier Accepts Leased Offer
        OrderResponse claimedRes = orderService.claimDispatch(orderId, courierId);
        assertThat(claimedRes.status()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(claimedRes.courierId()).isEqualTo(courierId);

        // 8. Courier En Route (Out for Delivery)
        OrderResponse outForDeliveryRes = orderService.outForDelivery(orderId, courierId);
        assertThat(outForDeliveryRes.status()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);

        // 9. Doorstep Handshake Delivery with Verification PIN
        // First try with invalid PIN -> must fail
        assertThatThrownBy(() -> orderService.deliverOrder(orderId, "000000"))
                .isInstanceOf(InvalidVerificationPinException.class);

        // Deliver with correct PIN -> captures payment, settles
        OrderResponse deliveredRes = orderService.deliverOrder(orderId, pin);
        assertThat(deliveredRes.status()).isEqualTo(OrderStatus.SETTLED);

        // 10. Verify Debezium Transactional Outbox Events emitted
        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(orderId);
        assertThat(events).isNotEmpty();
        List<String> eventTypes = events.stream().map(OutboxEvent::getEventType).toList();

        assertThat(eventTypes).contains(
                "OrderPlaced",
                "OrderPaymentPending",
                "OrderPaymentConfirmed",
                "OrderAccepted",
                "OrderPreparing",
                "OrderReadyForPickup",
                "OrderLeaseOffered",
                "OrderCourierAssigned",
                "OrderOutForDelivery",
                "OrderDelivered",
                "OrderSettled"
        );
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Verification Gate: Multi-Threaded Concurrency Race - 10 Couriers Compete for 1 Order Lease")
    void testConcurrentDispatchLeaseClaimRace() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(paymentServiceClient.holdPayment(any(), eq(userId), any(), any()))
                .thenReturn(new PaymentServiceClient.PaymentResult(true, "HOLD_SUCCESSFUL", null));

        CreateOrderRequest request = new CreateOrderRequest(
                userId,
                restaurantId,
                -122.4194, 37.7749,
                "Tower 1", "3", "301", null, null,
                "123456",
                idempotencyKey,
                List.of(new OrderItemRequest(UUID.randomUUID(), "Pasta", new BigDecimal("15.00"), 1, null))
        );

        OrderResponse order = orderService.createOrder(request);
        orderService.processPaymentHold(order.id());
        orderService.acceptOrder(order.id());
        orderService.prepareOrder(order.id());
        orderService.readyForPickup(order.id());

        // Issue 30-second lease
        orderService.offerDispatchLease(order.id(), null, 30);

        int numberOfCouriers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfCouriers);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(numberOfCouriers);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfCouriers; i++) {
            final UUID courierCandidate = UUID.randomUUID();
            futures.add(executor.submit(() -> {
                try {
                    startGate.await(); // wait until all 10 threads are ready to hit simultaneously
                    orderService.claimDispatch(order.id(), courierCandidate);
                    successCount.incrementAndGet();
                } catch (LeaseExpiredOrClaimedException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected exception
                } finally {
                    endGate.countDown();
                }
            }));
        }

        // Release the gate: all 10 couriers fire concurrently
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        // Mathematical proof of race safety:
        // Exactly 1 winner gets the assignment, 9 get LeaseExpiredOrClaimedException (zero double booking)
        assertThat(successCount.get())
                .as("Strictly exactly one courier should successfully claim the dispatch lease")
                .isEqualTo(1);

        assertThat(failureCount.get())
                .as("Strictly nine couriers should be rejected with lease conflict")
                .isEqualTo(9);

        Order finalOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(finalOrder.getCourierId()).isNotNull();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Verification Gate: Simulated Payment Stall -> Saga Daemon Aborts Order Stuck in PAYMENT_PENDING > 90s")
    void testSagaTimeoutCoordinatorAbortsStalePaymentOrder() {
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();

        // Mock payment hold returning a stall / network delay (returns false without error, leaving in PAYMENT_PENDING)
        when(paymentServiceClient.holdPayment(any(), eq(userId), any(), any()))
                .thenReturn(new PaymentServiceClient.PaymentResult(false, "STALLED", "NETWORK_TIMEOUT"));

        CreateOrderRequest request = new CreateOrderRequest(
                userId,
                restaurantId,
                -122.4194, 37.7749,
                "Pier 39", "1", "1", null, null,
                "555555",
                UUID.randomUUID(),
                List.of(new OrderItemRequest(UUID.randomUUID(), "Clam Chowder", new BigDecimal("12.00"), 1, null))
        );

        OrderResponse orderRes = orderService.createOrder(request);
        UUID orderId = orderRes.id();

        // Trigger payment hold attempt, which stalls and leaves order in PAYMENT_PENDING
        OrderResponse holdRes = orderService.processPaymentHold(orderId);
        assertThat(holdRes.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        // Native SQL update to age created_at past 90 seconds (Hibernate @Column updatable=false prevents entity save)
        Instant staleTimestamp = Instant.now().minusSeconds(95);
        int rowsUpdated = orderRepository.updateCreatedAt(orderId, staleTimestamp);
        assertThat(rowsUpdated).isEqualTo(1);

        // Run SagaTimeoutCoordinator daemon check
        int aborted = sagaCoordinator.abortStaleOrders(Duration.ofSeconds(90));
        assertThat(aborted).isGreaterThanOrEqualTo(1);

        // Verify order is now CANCELLED with reason PAYMENT_TIMEOUT
        Order abortedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(abortedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(abortedOrder.getCancellationReason()).isEqualTo("PAYMENT_TIMEOUT");

        // Verify Debezium Transactional Outbox recorded OrderCancelled event
        List<OutboxEvent> outboxEvents = outboxEventRepository.findByAggregateId(orderId);
        assertThat(outboxEvents)
                .extracting(OutboxEvent::getEventType)
                .contains("OrderCancelled");
    }
}
