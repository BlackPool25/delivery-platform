package com.delivery.order;

import com.delivery.order.dto.CreateOrderRequest;
import com.delivery.order.dto.OrderItemRequest;
import com.delivery.order.dto.OrderResponse;
import com.delivery.order.entity.OrderStatus;
import com.delivery.order.outbox.OutboxPartitionService;
import com.delivery.order.repository.OrderRepository;
import com.delivery.order.repository.OutboxEventRepository;
import com.delivery.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderOutboxPartitionVerificationTest {

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
    private OutboxPartitionService partitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static UUID createdOrderId;
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID RESTAURANT_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final UUID DISH_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    @Order(1)
    @DisplayName("Verification Gate Step 1: Place order with spatial dropoff (Point 4326), building, floor, room, gate code, and PIN")
    void testCreateOrderWithHyperLocalDropoffDetails() {
        CreateOrderRequest request = new CreateOrderRequest(
                USER_ID,
                RESTAURANT_ID,
                37.7891, // latitude
                -122.4014, // longitude
                "Salesforce Tower", // building (Deliverable 3)
                "Floor 42",          // floor (Deliverable 3)
                "Room 4208",         // room number (Deliverable 3)
                "4208#",             // gate code (Deliverable 3)
                "Leave at executive reception desk",
                "938104",            // verification pin
                UUID.randomUUID(),
                List.of(
                        new OrderItemRequest(DISH_ID, "Mission Burrito", new BigDecimal("14.50"), 2, "No onions"),
                        new OrderItemRequest(UUID.randomUUID(), "Horchata", new BigDecimal("4.00"), 1, "Extra cinnamon")
                )
        );

        OrderResponse response = orderService.createOrder(request);
        assertThat(response.id()).isNotNull();
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(response.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(response.dropoffBuilding()).isEqualTo("Salesforce Tower");
        assertThat(response.dropoffFloor()).isEqualTo("Floor 42");
        assertThat(response.dropoffRoom()).isEqualTo("Room 4208");
        assertThat(response.dropoffGateCode()).isEqualTo("4208#");
        assertThat(response.verificationPin()).isEqualTo("938104");
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("33.00"));
        assertThat(response.deliveryFee()).isEqualTo(new BigDecimal("3.99"));
        assertThat(response.totalAmount()).isGreaterThan(new BigDecimal("36.00"));
        assertThat(response.items()).hasSize(2);
        assertThat(response.version()).isEqualTo(0L);

        createdOrderId = response.id();

        // Verify spatial column type in PostgreSQL schema
        String udtName = jdbcTemplate.queryForObject(
                """
                SELECT udt_name FROM information_schema.columns
                WHERE table_name = 'orders' AND column_name = 'dropoff_location'
                """,
                String.class
        );
        assertThat(udtName).isEqualTo("geography");
    }

    @Test
    @Order(2)
    @DisplayName("Verification Gate Step 2: Atomic Transactional Outbox Event created for placed order")
    void testOutboxEventCreation() {
        var events = outboxEventRepository.findByAggregateId(createdOrderId);
        assertThat(events).isNotEmpty();
        var orderPlacedEvent = events.get(0);
        assertThat(orderPlacedEvent.getAggregateType()).isEqualTo("ORDER");
        assertThat(orderPlacedEvent.getEventType()).isEqualTo("OrderPlaced");
        assertThat(orderPlacedEvent.getPayload()).contains(createdOrderId.toString());
        assertThat(orderPlacedEvent.getPayload()).contains("Salesforce Tower");
        assertThat(orderPlacedEvent.isProcessed()).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("Verification Gate Step 3: Zero-WAL Outbox Partition Pruning via DROP TABLE")
    void testZeroWalOutboxPartitionPruning() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate expiredDate1 = today.minusDays(10);
        LocalDate expiredDate2 = today.minusDays(15);

        // Pre-create active and expired daily partitions
        String expiredPartition1 = partitionService.createDailyPartition(expiredDate1);
        String expiredPartition2 = partitionService.createDailyPartition(expiredDate2);
        String currentPartition = partitionService.createDailyPartition(today);

        List<String> partitionsBefore = partitionService.listPartitions();
        assertThat(partitionsBefore).contains(expiredPartition1, expiredPartition2, currentPartition);

        // Insert historical outbox records directly into the partitioned table with past timestamps
        String insertSql = """
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, processed, created_at)
            VALUES (?, 'ORDER', ?, 'OrderPlaced', '{"simulated": true}'::jsonb, true, ?::timestamptz)
            """;
        jdbcTemplate.update(insertSql, UUID.randomUUID(), UUID.randomUUID(), expiredDate1.atTime(12, 0).atOffset(ZoneOffset.UTC).toString());
        jdbcTemplate.update(insertSql, UUID.randomUUID(), UUID.randomUUID(), expiredDate2.atTime(12, 0).atOffset(ZoneOffset.UTC).toString());
        jdbcTemplate.update(insertSql, UUID.randomUUID(), UUID.randomUUID(), today.atTime(12, 0).atOffset(ZoneOffset.UTC).toString());

        long totalCountBefore = outboxEventRepository.countAllEvents();
        assertThat(totalCountBefore).isGreaterThanOrEqualTo(3);

        // Execute Zero-WAL Outbox Partition Pruning for partitions older than 7 days
        List<String> dropped = partitionService.prunePartitionsOlderThan(7);
        assertThat(dropped)
                .as("Partitions older than 7 days must be dropped")
                .contains(expiredPartition1, expiredPartition2)
                .doesNotContain(currentPartition);

        List<String> partitionsAfter = partitionService.listPartitions();
        assertThat(partitionsAfter)
                .doesNotContain(expiredPartition1, expiredPartition2)
                .contains(currentPartition);

        // Verification Gate requirement: Zero dead-tuple WAL overhead
        // DROP TABLE physically reclaims disk space instantly without dead tuples in pg_stat_user_tables
        long deadTuples = partitionService.getDeadTuplesCount();
        assertThat(deadTuples)
                .as("Zero dead row WAL overhead: DROP TABLE leaves 0 dead tuples")
                .isEqualTo(0L);
    }
}
