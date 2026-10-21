package com.delivery.tracking;

import com.delivery.tracking.model.CourierState;
import com.delivery.tracking.model.FraudFlag;
import com.delivery.tracking.model.TelemetryIngestResult;
import com.delivery.tracking.model.TelemetryPing;
import com.delivery.tracking.service.TrackingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KinematicAntiSpoofingVerificationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @Autowired
    private TrackingService trackingService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @Order(1)
    @DisplayName("Verification Gate: Stream 500 GPS pings; verify velocity > 120 km/h triggers instant anti-fraud drop and flag")
    void testKinematicVelocityFilterAcross500Pings() {
        UUID honestCourierId = UUID.randomUUID();
        UUID spoofingCourierId = UUID.randomUUID();

        // Base coordinates: Downtown San Francisco (Market & 4th St)
        double startLat = 37.7858;
        double startLon = -122.4065;
        Instant baseTime = Instant.now().minusSeconds(2000);

        int totalPings = 500;
        int fraudInjections = 10;
        int validPings = totalPings - fraudInjections;

        int acceptedCount = 0;
        int droppedFraudCount = 0;

        // 1. Stream 490 honest pings: Moving northwest at ~30 km/h (approx 25 meters every 3s)
        // 1 degree latitude ~ 111,000 meters -> 25 meters ~ 0.000225 degrees
        double latStep = 0.000225;
        double lonStep = 0.000150;

        for (int i = 0; i < validPings; i++) {
            double currentLat = startLat + (i * latStep * 0.1);
            double currentLon = startLon + (i * lonStep * 0.1);
            Instant pingTime = baseTime.plusSeconds(i * 3L);

            TelemetryPing ping = new TelemetryPing(
                    currentLat,
                    currentLon,
                    pingTime,
                    30.0,
                    45.0,
                    15.0,
                    5.0
            );

            TelemetryIngestResult result = trackingService.ingestTelemetry(honestCourierId, ping);

            assertThat(result.accepted())
                    .as("Honest ping %d should be accepted", i)
                    .isTrue();
            assertThat(result.status()).isEqualTo("ACCEPTED");
            assertThat(result.calculatedSpeedKmh()).isLessThanOrEqualTo(120.0);
            acceptedCount++;
        }

        assertThat(acceptedCount).isEqualTo(validPings);

        // Verify honest courier state in Redis
        CourierState latestHonestState = trackingService.getCourierState(honestCourierId);
        assertThat(latestHonestState).isNotNull();
        assertThat(latestHonestState.courierId()).isEqualTo(honestCourierId);
        assertThat(latestHonestState.h3Index()).isNotBlank();

        FraudFlag honestFraudFlag = trackingService.getFraudStatus(honestCourierId);
        assertThat(honestFraudFlag).isNull();

        // 2. Stream pings for spoofing courier: Starts honest, then attempts 10 teleportation spoof pings
        // Initial honest ping
        TelemetryPing initialPing = new TelemetryPing(
                startLat,
                startLon,
                baseTime,
                25.0,
                0.0,
                10.0,
                5.0
        );
        TelemetryIngestResult initialResult = trackingService.ingestTelemetry(spoofingCourierId, initialPing);
        assertThat(initialResult.accepted()).isTrue();

        // Inject 10 spoofing jumps: courier leaps to Oakland, San Jose, Los Angeles, etc. within 3 seconds!
        double[] spoofLatJumps = {
                37.8044, // Oakland (+10 km jump in 3s => ~12,000 km/h)
                37.3382, // San Jose (+65 km jump)
                34.0522, // Los Angeles (+550 km jump)
                40.7128, // New York (+4,000 km jump)
                51.5074, // London (+8,600 km jump)
                35.6762, // Tokyo (+8,200 km jump)
                37.7749, // Leap back to SF (+8,200 km jump)
                38.5816, // Sacramento (+120 km jump)
                36.1699, // Las Vegas (+660 km jump)
                32.7157  // San Diego (+750 km jump)
        };

        Instant spoofBaseTime = baseTime.plusSeconds(3);

        for (int j = 0; j < fraudInjections; j++) {
            Instant jumpTime = spoofBaseTime.plusSeconds(j * 3L + 3);
            TelemetryPing spoofPing = new TelemetryPing(
                    spoofLatJumps[j],
                    startLon, // Same longitude, massive latitude teleport
                    jumpTime,
                    30.0,
                    0.0,
                    10.0,
                    5.0
            );

            TelemetryIngestResult spoofResult = trackingService.ingestTelemetry(spoofingCourierId, spoofPing);

            assertThat(spoofResult.accepted())
                    .as("Spoofed ping %d must be rejected", j)
                    .isFalse();
            assertThat(spoofResult.status()).isEqualTo("FLAGGED_SPOOFING");
            assertThat(spoofResult.calculatedSpeedKmh()).isGreaterThan(120.0);
            droppedFraudCount++;
        }

        assertThat(droppedFraudCount).isEqualTo(fraudInjections);
        assertThat(acceptedCount + droppedFraudCount).isEqualTo(totalPings);

        // 3. Verify that the spoofing courier has been flagged in Redis and dropped
        FraudFlag fraudFlag = trackingService.getFraudStatus(spoofingCourierId);
        assertThat(fraudFlag).isNotNull();
        assertThat(fraudFlag.courierId()).isEqualTo(spoofingCourierId);
        assertThat(fraudFlag.calculatedSpeedKmh()).isGreaterThan(120.0);
        assertThat(fraudFlag.maxAllowedSpeedKmh()).isEqualTo(120.0);
        assertThat(fraudFlag.reason()).contains("Kinematic velocity threshold exceeded");

        // Verify courier is in the flagged couriers set in Redis
        Boolean isMember = stringRedisTemplate.opsForSet().isMember("tracking:flagged_couriers", spoofingCourierId.toString());
        assertThat(isMember).isTrue();

        // Verify the latest location of the spoofing courier was NOT corrupted with the spoofed coordinates
        CourierState spoofingState = trackingService.getCourierState(spoofingCourierId);
        assertThat(spoofingState).isNotNull();
        assertThat(spoofingState.latitude()).isEqualTo(startLat);
        assertThat(spoofingState.longitude()).isEqualTo(startLon);

        // 4. Test spatial search for nearby couriers
        List<CourierState> nearby = trackingService.findNearbyCouriers(startLat, startLon, 5.0);
        assertThat(nearby).isNotEmpty();
        assertThat(nearby.stream().anyMatch(c -> c.courierId().equals(spoofingCourierId))).isTrue();
    }
}
