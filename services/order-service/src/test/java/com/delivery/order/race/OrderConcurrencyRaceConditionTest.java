package com.delivery.order.race;

import com.delivery.order.client.PaymentServiceClient;
import com.delivery.order.dto.CreateOrderRequest;
import com.delivery.order.dto.OrderItemRequest;
import com.delivery.order.dto.OrderResponse;
import com.delivery.order.entity.Order;
import com.delivery.order.entity.OrderStatus;
import com.delivery.order.exception.LeaseExpiredOrClaimedException;
import com.delivery.order.repository.OrderRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 6 Multi-Threaded Concurrency & Race Condition Verification")
public class OrderConcurrencyRaceConditionTest {

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

    @MockitoBean
    private PaymentServiceClient paymentServiceClient;

    @org.junit.jupiter.api.BeforeEach
    void setUpPaymentMock() {
        when(paymentServiceClient.holdPayment(any(), any(), any(), any()))
                .thenReturn(new PaymentServiceClient.PaymentResult(true, "HOLD_OK", null));
        when(paymentServiceClient.capturePayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentServiceClient.PaymentResult(true, "CAPTURE_OK", null));
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Verification Gate: 50 Concurrent Couriers Dispatch Claim Race (Virtual Threads Contention)")
    void testFiftyCouriersConcurrentDispatchClaimRace() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();

        CreateOrderRequest request = new CreateOrderRequest(
                userId, restaurantId, -122.4194, 37.7749,
                "Transamerica Pyramid", "48", "4801", "GATE#123", "Leave at lobby",
                "123456", UUID.randomUUID(),
                List.of(new OrderItemRequest(UUID.randomUUID(), "Espresso", new BigDecimal("4.50"), 2, null))
        );

        OrderResponse order = orderService.createOrder(request);
        orderService.processPaymentHold(order.id());
        orderService.acceptOrder(order.id());
        orderService.prepareOrder(order.id());
        orderService.readyForPickup(order.id());
        orderService.offerDispatchLease(order.id(), null, 30);

        int totalCouriers = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalCouriers);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger rejectionCounter = new AtomicInteger(0);
        List<UUID> successfulCouriers = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalCouriers; i++) {
                final UUID candidateCourier = UUID.randomUUID();
                virtualExecutor.submit(() -> {
                    try {
                        startGate.await();
                        orderService.claimDispatch(order.id(), candidateCourier);
                        successCounter.incrementAndGet();
                        successfulCouriers.add(candidateCourier);
                    } catch (LeaseExpiredOrClaimedException e) {
                        rejectionCounter.incrementAndGet();
                    } catch (Exception e) {
                        // Other unexpected exception
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            // Fire all 50 virtual threads simultaneously
            startGate.countDown();
            boolean completed = endGate.await(10, TimeUnit.SECONDS);

            assertThat(completed).isTrue();
            // Invariant: Exactly 1 courier must win the claim
            assertThat(successCounter.get())
                    .as("Exactly 1 courier must successfully acquire the 1-to-1 dispatch lease")
                    .isEqualTo(1);
            // Invariant: Exactly 49 couriers must be rejected
            assertThat(rejectionCounter.get())
                    .as("All 49 other couriers must receive LeaseExpiredOrClaimedException")
                    .isEqualTo(49);
            assertThat(successfulCouriers).hasSize(1);

            Order persisted = orderRepository.findById(order.id()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
            assertThat(persisted.getCourierId()).isEqualTo(successfulCouriers.get(0));
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Verification Gate: Idempotent Concurrent Order Placement (10 Parallel Checkout Requests)")
    void testConcurrentIdempotentOrderPlacement() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID sharedIdempotencyKey = UUID.randomUUID();

        CreateOrderRequest request = new CreateOrderRequest(
                userId, restaurantId, -122.4194, 37.7749,
                "Salesforce Tower", "61", "SkyLounge", null, null,
                "654321", sharedIdempotencyKey,
                List.of(new OrderItemRequest(UUID.randomUUID(), "Poke Bowl", new BigDecimal("16.00"), 1, "No onions"))
        );

        int threads = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);
        List<OrderResponse> responses = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                virtualExecutor.submit(() -> {
                    try {
                        startGate.await();
                        OrderResponse res = orderService.createOrder(request);
                        responses.add(res);
                    } catch (Exception e) {
                        // ignore or record
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean completed = endGate.await(10, TimeUnit.SECONDS);

            assertThat(completed).isTrue();
            assertThat(responses).isNotEmpty();

            // All successful responses must return the EXACT same order ID (idempotency preserved)
            UUID expectedOrderId = responses.get(0).id();
            for (OrderResponse res : responses) {
                assertThat(res.id()).isEqualTo(expectedOrderId);
            }

            // Verify database contains strictly 1 row for this idempotency key
            long count = orderRepository.findAll().stream()
                    .filter(o -> sharedIdempotencyKey.equals(o.getIdempotencyKey()))
                    .count();
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Verification Gate: Expired Dispatch Lease Rejection (dispatch_lease_expires_at <= NOW())")
    void testExpiredDispatchLeaseRejection() {
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();

        CreateOrderRequest request = new CreateOrderRequest(
                userId, restaurantId, -122.4194, 37.7749,
                "Ferry Building", "1", "Shop 5", null, null,
                "999888", UUID.randomUUID(),
                List.of(new OrderItemRequest(UUID.randomUUID(), "Sourdough Bread", new BigDecimal("9.00"), 1, null))
        );

        OrderResponse order = orderService.createOrder(request);
        orderService.processPaymentHold(order.id());
        orderService.acceptOrder(order.id());
        orderService.prepareOrder(order.id());
        orderService.readyForPickup(order.id());

        // Offer lease, then age lease timestamp so it is strictly expired (lease_expires_at <= NOW())
        orderService.offerDispatchLease(order.id(), null, 30);
        Order persisted = orderRepository.findById(order.id()).orElseThrow();
        persisted.setDispatchLeaseExpiresAt(java.time.Instant.now().minusSeconds(10));
        orderRepository.save(persisted);

        // Attempting to claim an expired lease must throw LeaseExpiredOrClaimedException
        UUID courierId = UUID.randomUUID();
        try {
            orderService.claimDispatch(order.id(), courierId);
            org.junit.jupiter.api.Assertions.fail("Should have thrown LeaseExpiredOrClaimedException for expired lease");
        } catch (LeaseExpiredOrClaimedException e) {
            assertThat(e.getMessage()).contains("expired or already claimed");
        }
    }
}
