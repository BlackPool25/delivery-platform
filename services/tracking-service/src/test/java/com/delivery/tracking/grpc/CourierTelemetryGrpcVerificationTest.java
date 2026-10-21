package com.delivery.tracking.grpc;

import com.delivery.tracking.model.CourierState;
import com.delivery.tracking.service.TrackingService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "grpc.server.port=0",
        "grpc.server.enabled=true"
})
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CourierTelemetryGrpcVerificationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @Autowired
    private GrpcServerLifecycleManager grpcServerManager;

    @Autowired
    private TrackingService trackingService;

    private ManagedChannel channel;
    private CourierTelemetryServiceGrpc.CourierTelemetryServiceBlockingStub blockingStub;
    private CourierTelemetryServiceGrpc.CourierTelemetryServiceStub asyncStub;

    @BeforeEach
    void setUp() {
        int port = grpcServerManager.getPort();
        channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        blockingStub = CourierTelemetryServiceGrpc.newBlockingStub(channel);
        asyncStub = CourierTelemetryServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Verification Gate: Unary gRPC Telemetry Ingestion & Real-Time Query")
    void testUnaryTelemetryIngestionAndQuery() {
        UUID courierId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        TelemetryPingRequest request = TelemetryPingRequest.newBuilder()
                .setCourierId(courierId.toString())
                .setLatitude(37.7749)
                .setLongitude(-122.4194)
                .setTimestampEpochMs(now)
                .setSpeedKmh(22.5)
                .setHeading(90.0)
                .setAccuracy(4.2)
                .build();

        TelemetryResponse response = blockingStub.ingestTelemetry(request);

        assertThat(response.getAccepted()).isTrue();
        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
        assertThat(response.getCalculatedSpeedKmh()).isEqualTo(0.0); // first ping has delta distance 0

        // Query location via gRPC
        CourierLocationResponse loc = blockingStub.getCourierLocation(
                CourierLocationRequest.newBuilder().setCourierId(courierId.toString()).build()
        );

        assertThat(loc.getFound()).isTrue();
        assertThat(loc.getCourierId()).isEqualTo(courierId.toString());
        assertThat(loc.getLatitude()).isEqualTo(37.7749);
        assertThat(loc.getLongitude()).isEqualTo(-122.4194);
        assertThat(loc.getH3Index()).isNotEmpty();
        assertThat(loc.getIsFlagged()).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Verification Gate: Client Streaming gRPC Telemetry (High-Frequency Pipeline)")
    void testClientStreamingTelemetry() throws InterruptedException {
        UUID courierId = UUID.randomUUID();
        int pingCount = 20;
        CountDownLatch finishLatch = new CountDownLatch(1);
        AtomicReference<TelemetryStreamSummary> summaryRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamObserver<TelemetryStreamSummary> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(TelemetryStreamSummary summary) {
                summaryRef.set(summary);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                finishLatch.countDown();
            }
        };

        StreamObserver<TelemetryPingRequest> requestObserver = asyncStub.streamTelemetry(responseObserver);

        long baseTime = System.currentTimeMillis();
        // Emulate walking/biking along Market St in San Francisco (~10 meters every 3s)
        for (int i = 0; i < pingCount; i++) {
            double lat = 37.7749 + (i * 0.0001);
            double lon = -122.4194 + (i * 0.0001);
            long ts = baseTime + (i * 3000);

            TelemetryPingRequest ping = TelemetryPingRequest.newBuilder()
                    .setCourierId(courierId.toString())
                    .setLatitude(lat)
                    .setLongitude(lon)
                    .setTimestampEpochMs(ts)
                    .setSpeedKmh(15.0)
                    .setHeading(45.0)
                    .setAccuracy(3.0)
                    .build();

            requestObserver.onNext(ping);
        }

        requestObserver.onCompleted();

        boolean completedInTime = finishLatch.await(5, TimeUnit.SECONDS);
        assertThat(completedInTime).isTrue();
        assertThat(errorRef.get()).isNull();

        TelemetryStreamSummary summary = summaryRef.get();
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalReceived()).isEqualTo(pingCount);
        assertThat(summary.getTotalAccepted()).isEqualTo(pingCount);
        assertThat(summary.getTotalRejected()).isEqualTo(0);

        CourierState state = trackingService.getCourierState(courierId);
        assertThat(state).isNotNull();
        assertThat(state.isFlagged()).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("Verification Gate: Kinematic GPS Anti-Spoofing Filter via gRPC (> 120 km/h Teleportation)")
    void testKinematicAntiSpoofingOverGrpc() {
        UUID courierId = UUID.randomUUID();
        long t0 = System.currentTimeMillis();

        // 1. Initial valid ping in San Francisco
        TelemetryPingRequest ping1 = TelemetryPingRequest.newBuilder()
                .setCourierId(courierId.toString())
                .setLatitude(37.7749)
                .setLongitude(-122.4194)
                .setTimestampEpochMs(t0)
                .setSpeedKmh(20.0)
                .build();
        TelemetryResponse res1 = blockingStub.ingestTelemetry(ping1);
        assertThat(res1.getAccepted()).isTrue();

        // 2. Fraudulent teleportation ping 2 seconds later in Oakland (~12 km away -> > 20,000 km/h)
        TelemetryPingRequest ping2 = TelemetryPingRequest.newBuilder()
                .setCourierId(courierId.toString())
                .setLatitude(37.8044)
                .setLongitude(-122.2712)
                .setTimestampEpochMs(t0 + 2000)
                .setSpeedKmh(30.0)
                .build();
        TelemetryResponse res2 = blockingStub.ingestTelemetry(ping2);

        // Verification Gate: gRPC call returns rejected spoofing
        assertThat(res2.getAccepted()).isFalse();
        assertThat(res2.getStatus()).isEqualTo("FLAGGED_SPOOFING");
        assertThat(res2.getCalculatedSpeedKmh()).isGreaterThan(120.0);

        // Query fraud status via gRPC
        CourierLocationResponse loc = blockingStub.getCourierLocation(
                CourierLocationRequest.newBuilder().setCourierId(courierId.toString()).build()
        );
        assertThat(loc.getIsFlagged()).isTrue();
        assertThat(loc.getFraudReason()).contains("threshold exceeded");
    }

    @Test
    @Order(4)
    @DisplayName("Verification Gate: High-Concurrency Virtual Threading gRPC Stress (100 parallel calls)")
    void testVirtualThreadingGrpcConcurrency() throws InterruptedException {
        int totalRequests = 100;
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger errorCounter = new AtomicInteger();

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalRequests; i++) {
                final int index = i;
                virtualExecutor.submit(() -> {
                    try {
                        UUID courierId = UUID.randomUUID();
                        TelemetryPingRequest ping = TelemetryPingRequest.newBuilder()
                                .setCourierId(courierId.toString())
                                .setLatitude(37.7749 + (index * 0.00001))
                                .setLongitude(-122.4194 + (index * 0.00001))
                                .setTimestampEpochMs(System.currentTimeMillis())
                                .setSpeedKmh(25.0)
                                .build();

                        TelemetryResponse response = blockingStub.ingestTelemetry(ping);
                        if (response.getAccepted()) {
                            successCounter.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCounter.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(successCounter.get()).isEqualTo(totalRequests);
            assertThat(errorCounter.get()).isEqualTo(0);
        }
    }
}
